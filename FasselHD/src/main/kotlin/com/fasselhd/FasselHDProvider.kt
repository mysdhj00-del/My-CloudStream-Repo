package com.fasselhd

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * FasselHD (fasselhd.com) provider for CloudStream 3.
 *
 * The website's own data panel (flech.tn) is dead — the front-end renders
 * empty pages for everyone. The operator's official Android app, however,
 * reads a remote config (watchit.tn/api_urls.json) that points to a LIVE
 * backup of the same panel API; that base was verified live on 2026-10-18
 * (catalog totals ~29.8k movies / ~10.6k series, updated the same day):
 *
 *   base    = https://kahitdgku.com/faselhd15/public/api
 *   lists   = networks/media/show/{slug-or-network-id}/1?page=N (Laravel)
 *   home    = media/homecontent/1   (named sections in one call)
 *   search  = search/{query}/1      -> {"search":[...]}
 *   movie   = media/detail/{id}/1   -> detail incl. videos[] (server list)
 *   series  = series/show/{id}/1    -> detail incl. seasons[{id,...}]
 *             series/season/{seasonId}/1 -> {"episodes":[...]}
 *             series/episodeshow/{episodeId}/1 -> episodes[0].videos[]
 *
 * Each video row is {server, link, tmp_link, header (referer), useragent,
 * hls, embed, lang, hd}. Server/link classes seen live: StreamWish
 * (extractor-known), vidtube.one + updown.icu embeds (walled against
 * datacenter IPs; the phone is the intended client, so the dig runs
 * phone-side with the row's own UA/referer), fasel-hd.com WordPress pages
 * ("VIP Fast", tmp_link on faselhd.center), vidsrc.sh embeds, and API-style
 * endpoints (admin.dramaramadan.net, eisk.badmoussi.com) whose JSON/text
 * bodies are scanned for media/embed URLs. Fallback for unresolved embeds
 * is the relay's generic /relay/resolve (same chain as Brestj v5).
 *
 * Resilience (copied from the official app's own design): if the primary
 * API base stops answering, the provider re-reads the operator's remote
 * config once and adopts the base published there.
 *
 * Internal data URLs: "movie:{id}", "serie:{id}", "anime:{id}" for load();
 * "episode:{episodeId}" as episode data for loadLinks().
 */
class FasselHDProvider : MainAPI() {
    override var mainUrl = "https://fasselhd.com"
    override var name = "FasselHD"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val primaryApi = "https://kahitdgku.com/faselhd15/public/api"
    private val configUrl = "https://watchit.tn/api_urls.json"
    private val relayBase = "https://egydead-stremio.onrender.com"

    @Volatile private var liveBase: String? = null
    @Volatile private var configBase: String? = null
    @Volatile private var configTried = false

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "home" to "المميزة والرائجة",
        "net:vide-vide-created-vide-movie" to "أفلام",
        "net:vide-vide-created-vide-serie" to "مسلسلات",
        "net:vide-vide-vide-vide-modablej" to "مدبلج",
        "neta:7869" to "أنمي",
        "net:213" to "مسلسلات نتفلكس",
        "net:8618" to "مسلسلات رمضان 2026"
    )

    // ---------- small JSON helpers (the API mixes nulls, "None" strings,
    // and numbers-as-strings, so everything is read tolerantly) ----------

    private fun s(o: JSONObject?, key: String): String {
        if (o == null) return ""
        val v = o.opt(key) ?: return ""
        if (v == JSONObject.NULL) return ""
        val t = v.toString().trim()
        return if (t.equals("null", true) || t.equals("none", true)) "" else t
    }

    private fun arr(o: JSONObject?, key: String): JSONArray? {
        if (o == null) return null
        val v = o.opt(key)
        return v as? JSONArray
    }

    private fun yearOf(date: String): Int? =
        Regex("""(19|20)\d{2}""").find(date)?.value?.toIntOrNull()

    // ---------- API transport with base failover ----------

    /** GETs a path on the live API base; returns the body or null. */
    private suspend fun apiGet(path: String): String? {
        val bases = ArrayList<String>()
        liveBase?.let { bases += it }
        if (primaryApi !in bases) bases += primaryApi
        if (!configTried) {
            configTried = true
            configBase = runCatching {
                val cfg = app.get(configUrl, headers = browserHeaders).text
                JSONObject(cfg).optString("backupApiUrlNoureddine").trim().trimEnd('/')
            }.getOrNull()?.ifBlank { null }
        }
        configBase?.let { if (it !in bases) bases += it }
        for (base in bases) {
            val res = runCatching { app.get("$base/$path", headers = browserHeaders) }.getOrNull()
            val text = res?.text
            if (res != null && res.code == 200 && text != null &&
                (text.startsWith("{") || text.startsWith("["))
            ) {
                liveBase = base
                return text
            }
        }
        return null
    }

    private suspend fun apiJson(path: String): JSONObject? {
        val text = apiGet(path) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    // ---------- cards ----------

    private fun rowToCard(row: JSONObject, forceAnime: Boolean = false): SearchResponse? {
        val id = s(row, "id")
        if (id.isBlank()) return null
        val title = s(row, "title").ifBlank { s(row, "name") }
        if (title.isBlank()) return null
        val poster = s(row, "poster_path").ifBlank { null }
        val type = s(row, "type").lowercase()
        val isAnime = forceAnime || s(row, "is_anime") == "1"
        val year = yearOf(s(row, "release_date").ifBlank { s(row, "first_air_date") })
        return when {
            type == "movie" ->
                newMovieSearchResponse(title, "movie:$id", TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            isAnime ->
                newAnimeSearchResponse(title, "anime:$id", TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            else ->
                newTvSeriesSearchResponse(title, "serie:$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
        }
    }

    private fun rowsToCards(rows: JSONArray?, forceAnime: Boolean = false): List<SearchResponse> {
        if (rows == null) return emptyList()
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val card = runCatching { rowToCard(row, forceAnime) }.getOrNull() ?: continue
            if (seen.add(card.url)) out += card
        }
        return out
    }

    // ---------- MainAPI ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        if (data == "home") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val root = apiJson("media/homecontent/1")
                ?: return newHomePageResponse(request.name, emptyList(), false)
            val merged = ArrayList<SearchResponse>()
            val seen = HashSet<String>()
            for (key in listOf(
                "trending", "featured", "latest", "top10", "popular",
                "popularSeries", "recommended", "recents", "anime"
            )) {
                val cards = rowsToCards(arr(root, key), forceAnime = key == "anime")
                for (c in cards) if (seen.add(c.url)) merged += c
            }
            return newHomePageResponse(request.name, merged, false)
        }
        val isAnimeNet = data.startsWith("neta:")
        val net = data.removePrefix("neta:").removePrefix("net:")
        val root = apiJson("networks/media/show/$net/1?page=$page")
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = rowsToCards(arr(root, "data"), forceAnime = isAnimeNet)
        val hasNext = s(root, "current_page").toIntOrNull() != null &&
            s(root, "current_page").toInt() < (s(root, "last_page").toIntOrNull() ?: 1)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val root = apiJson("search/$enc/1") ?: return emptyList()
        return rowsToCards(arr(root, "search"))
    }

    /**
     * Extracts the (kind, id) token from a data string. CloudStream resolves
     * card URLs against mainUrl before load() (a card stored as "movie:56442"
     * arrives as "https://fasselhd.com/movie:56442"), so the token is matched
     * anywhere in the string rather than parsed positionally.
     */
    private fun parseToken(data: String): Pair<String, String>? {
        val m = Regex("""(movie|serie|anime|episode):(\d+)""").find(data) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    override suspend fun load(url: String): LoadResponse {
        val (kind, id) = parseToken(url) ?: throw ErrorLoadingException("Unknown kind")
        return when (kind) {
            "movie" -> loadMovie(id, url)
            "serie", "anime" -> loadSeries(id, url, kind == "anime")
            else -> throw ErrorLoadingException("Unknown kind")
        }
    }

    private fun actorsOf(root: JSONObject): List<ActorData>? {
        val cast = arr(root, "casterslist") ?: return null
        val out = ArrayList<ActorData>()
        for (i in 0 until minOf(cast.length(), 20)) {
            val c = cast.optJSONObject(i) ?: continue
            val n = s(c, "name")
            if (n.isNotBlank()) {
                out += ActorData(Actor(n, s(c, "profile_path").ifBlank { null }))
            }
        }
        return out.ifEmpty { null }
    }

    private fun tagsOf(root: JSONObject): List<String>? {
        val g = arr(root, "genres") ?: return null
        val out = ArrayList<String>()
        for (i in 0 until g.length()) {
            val name = s(g.optJSONObject(i), "name")
            if (name.isNotBlank()) out += name
        }
        return out.ifEmpty { null }
    }

    private suspend fun loadMovie(id: String, url: String): LoadResponse {
        val root = apiJson("media/detail/$id/1") ?: throw ErrorLoadingException("No detail")
        val title = s(root, "title").ifBlank { s(root, "original_name") }
        if (title.isBlank()) throw ErrorLoadingException("No title")
        return newMovieLoadResponse(title, url, TvType.Movie, "movie:$id") {
            this.posterUrl = s(root, "poster_path").ifBlank { null }
            this.backgroundPosterUrl = s(root, "backdrop_path").ifBlank { null }
            this.plot = s(root, "overview").ifBlank { null }
            this.year = yearOf(s(root, "release_date"))
            this.tags = tagsOf(root)
            this.actors = actorsOf(root)
        }
    }

    private suspend fun loadSeries(id: String, url: String, anime: Boolean): LoadResponse {
        val root = apiJson("series/show/$id/1") ?: throw ErrorLoadingException("No detail")
        val title = s(root, "name").ifBlank { s(root, "original_name") }
        if (title.isBlank()) throw ErrorLoadingException("No title")
        val seasons = arr(root, "seasons")
        val seasonList = ArrayList<JSONObject>()
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                seasons.optJSONObject(i)?.let { seasonList += it }
            }
        }
        seasonList.sortBy { s(it, "season_number").toIntOrNull() ?: 0 }
        val episodes = ArrayList<Episode>()
        for (season in seasonList) {
            val seasonId = s(season, "id")
            val seasonNum = s(season, "season_number").toIntOrNull() ?: 1
            if (seasonId.isBlank()) continue
            val epRoot = apiJson("series/season/$seasonId/1") ?: continue
            val epRows = arr(epRoot, "episodes") ?: continue
            for (i in 0 until epRows.length()) {
                val ep = epRows.optJSONObject(i) ?: continue
                val epId = s(ep, "id")
                if (epId.isBlank()) continue
                val epNum = s(ep, "episode_number").toIntOrNull()
                episodes += newEpisode("episode:$epId") {
                    this.name = s(ep, "name").ifBlank { epNum?.let { n -> "الحلقة $n" } }
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = s(ep, "still_path").ifBlank { null }
                }
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(
            title, url,
            if (anime) TvType.Anime else TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = s(root, "poster_path").ifBlank { null }
            this.backgroundPosterUrl = s(root, "backdrop_path").ifBlank { null }
            this.plot = s(root, "overview").ifBlank { null }
            this.year = yearOf(s(root, "first_air_date"))
            this.tags = tagsOf(root)
            this.actors = actorsOf(root)
        }
    }

    // ---------- playback ----------

    private data class ServerRow(
        val server: String,
        val link: String,
        val tmpLink: String?,
        val referer: String?,
        val extraHeaders: Map<String, String>,
        val userAgent: String?
    )

    /**
     * The API's video "header" field is polymorphic (measured 2026-10-18):
     * a plain URL means Referer ("https://faselhd.center/"), while other
     * rows carry a pipe-separated header blob meant for the player
     * ("Accept-Encoding:identity|connection:keep-alive" on the seriesmp4
     * rows). Parse each shape into its proper place.
     */
    private fun parseHeaderField(raw: String): Pair<String?, Map<String, String>> {
        val t = raw.trim()
        if (t.isBlank()) return null to emptyMap()
        if (t.startsWith("http")) return t to emptyMap()
        val extra = LinkedHashMap<String, String>()
        if (t.contains(":")) {
            for (part in t.split("|")) {
                val kv = part.split(":", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim()
                    val v = kv[1].trim()
                    if (k.isNotBlank() && v.isNotBlank() &&
                        k.all { it.isLetterOrDigit() || it == '-' }
                    ) extra[k] = v
                }
            }
        }
        return null to extra
    }

    /** Pulls the videos[] server list for a movie id or an episode id. */
    private suspend fun fetchServers(data: String): List<ServerRow> {
        val (kind, id) = parseToken(data) ?: return emptyList()
        val videos: JSONArray? = when (kind) {
            "movie" -> arr(apiJson("media/detail/$id/1"), "videos")
            "episode" -> {
                val root = apiJson("series/episodeshow/$id/1")
                val eps = arr(root, "episodes")
                val first = if (eps != null && eps.length() > 0) eps.optJSONObject(0) else null
                arr(first, "videos")
            }
            else -> null
        }
        if (videos == null) return emptyList()
        val out = ArrayList<ServerRow>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val link = s(v, "link")
            if (link.isBlank()) continue
            val (ref, extra) = parseHeaderField(s(v, "header"))
            out += ServerRow(
                server = s(v, "server").ifBlank { "Server" },
                link = link,
                tmpLink = s(v, "tmp_link").ifBlank { null },
                referer = ref,
                extraHeaders = extra,
                userAgent = s(v, "useragent").ifBlank { null }
            )
        }
        return out
    }

    private val extractorHosts = listOf(
        "streamwish", "wishcdn", "doodstream", "dood", "voe.", "mixdrop",
        "uqload", "vidhide", "earnvids", "filemoon", "streamtape", "vidmoly",
        "streamhg", "hgcloud", "byse", "mp4upload", "vidoza",
        "vidtube", "firestream"
    )

    private fun isExtractorKnown(url: String): Boolean =
        extractorHosts.any { url.contains(it) }

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4")
    }

    /**
     * Scans a page/JSON text for a direct media URL (JW file:, an ArtPlayer
     * videoUrl, or a bare .m3u8/.mp4). Mirrors the relay's resolver scan.
     */
    private fun extractDirect(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(clean)?.let { return it.value }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(clean)?.let { return it.value }
        return null
    }

    // Dean Edwards packer unpacker (same proven port as Brestj/MyCima):
    // several embed hosts hide the JW config in a packed block.
    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun unpackPackedJs(text: String): List<String> {
        val out = mutableListOf<String>()
        for (m in packedRegex.findAll(text)) {
            val payload = m.groupValues[1]
            val radix = m.groupValues[2].toIntOrNull() ?: continue
            val count = m.groupValues[3].toIntOrNull() ?: continue
            if (radix < 2 || radix > 36 || count <= 0 || count > 5000) continue
            val keywords = m.groupValues[4].split("|")
            val map = HashMap<String, String>()
            for (i in 0 until count) {
                val tok = i.toString(radix)
                val kw = keywords.getOrNull(i)
                map[tok] = if (kw.isNullOrEmpty()) tok else kw
            }
            out += Regex("""\b\w+\b""").replace(payload) { mr -> map[mr.value] ?: mr.value }
        }
        return out
    }

    private fun pageHeaders(ua: String?, referer: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua?.ifBlank { null } ?: browserUA
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        return h
    }

    /**
     * Streaming probe of a candidate direct URL: opens the connection,
     * reads AT MOST 512 bytes, disconnects. Returns 1 when the response
     * looks like media, 0 when it is provably not (HTTP error, an HTML
     * body, or a playlist URL whose body is not a playlist), -1 when the
     * probe itself could not decide. This exists because the operator's
     * "direct .mp4" rows can be HTML WRAPPER pages (seriesmp4 CDN: the
     * .mp4 URL serves a page hiding the real file in an iframe) — feeding
     * one to the player raw is exactly ExoPlayer's
     * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (measured on-device
     * 2026-10-18, The Odyssey).
     */
    private fun probeMedia(
        url: String,
        referer: String?,
        ua: String?,
        extra: Map<String, String> = emptyMap()
    ): Int {
        return try {
            val conn = (java.net.URL(url).openConnection() as? java.net.HttpURLConnection)
                ?: return -1
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("Range", "bytes=0-511")
            conn.setRequestProperty("User-Agent", ua?.ifBlank { null } ?: browserUA)
            if (!referer.isNullOrBlank() && asciiSafe(referer)) {
                conn.setRequestProperty("Referer", referer)
            }
            for ((k, v) in extra) {
                if (asciiSafe(k) && asciiSafe(v)) conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return 0
            }
            val ctype = (conn.contentType ?: "").lowercase()
            val buf = ByteArray(512)
            var n = 0
            val ins = conn.inputStream
            while (n < 512) {
                val r = ins.read(buf, n, 512 - n)
                if (r <= 0) break
                n += r
            }
            conn.disconnect()
            val head = String(buf, 0, n, Charsets.UTF_8).trimStart()
            when {
                url.contains(".m3u8") -> if (head.startsWith("#EXTM3U")) 1 else 0
                ctype.contains("text/html") || head.startsWith("<") -> 0
                else -> 1
            }
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Phone-side dig: fetch an embed/API page with the row's own identity,
     * then — in order — a media-bearing iframe src (the seriesmp4 wrapper
     * hides a Yandex-Disk downloader URL in a hidden iframe; it is the
     * real file), the raw-text scan, the unpacked packed-JS scan, and one
     * iframe/embed chase (vidsrc / WordPress player pages / API endpoints
     * that answer with an embed reference).
     */
    private suspend fun digForDirect(
        url: String,
        referer: String?,
        ua: String?,
        depth: Int = 0
    ): String? {
        val rawText = runCatching {
            app.get(url, headers = pageHeaders(ua, referer)).text
        }.getOrNull() ?: return null
        val text = rawText.replace("&amp;", "&")
        val iframeSrc = Regex("""<iframe[^>]+src\s*=\s*["'](https?://[^"']+)["']""")
            .find(text)?.groupValues?.get(1)
        if (iframeSrc != null &&
            (iframeSrc.contains(".mp4") || iframeSrc.contains(".m3u8") ||
                iframeSrc.contains("downloader.") || iframeSrc.contains("storage."))
        ) {
            return iframeSrc
        }
        extractDirect(text)?.let { return it }
        for (plain in unpackPackedJs(text)) {
            extractDirect(plain)?.let { return it }
        }
        if (depth >= 1) return null
        val chase = iframeSrc
            ?: Regex("""https?://[^\s"'<>]+(?:/embed|/e/|/watch)[^\s"'<>]*""")
                .find(text.replace("\\/", "/"))?.value
        if (chase != null && chase != url && !chase.contains("google") &&
            !chase.contains("facebook") && !chase.contains("doubleclick")
        ) {
            return digForDirect(chase, url, ua, depth + 1)
        }
        return null
    }

    private fun asciiSafe(v: String): Boolean = v.all { it.code < 128 }

    private suspend fun emitDirect(
        label: String,
        direct: String,
        referer: String,
        ua: String?,
        callback: (ExtractorLink) -> Unit,
        extra: Map<String, String> = emptyMap()
    ) {
        val headers = LinkedHashMap<String, String>()
        if (referer.isNotBlank() && asciiSafe(referer)) headers["Referer"] = referer
        headers["User-Agent"] = ua?.ifBlank { null } ?: browserUA
        for ((k, v) in extra) {
            if (asciiSafe(k) && asciiSafe(v)) headers[k] = v
        }
        callback(
            newExtractorLink(
                name, label, direct,
                if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
    }

    /** Stage 3: the relay fetches the embed and scans it from its side. */
    private suspend fun resolveViaRelay(
        embed: String,
        referer: String?,
        label: String,
        ua: String?,
        callback: (ExtractorLink) -> Unit,
        extra: Map<String, String> = emptyMap()
    ): Int {
        val relayUrl = "$relayBase/relay/resolve?url=" +
            java.net.URLEncoder.encode(embed, "UTF-8") +
            "&ref=" + java.net.URLEncoder.encode(referer ?: mainUrl, "UTF-8")
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(relayUrl).text }.getOrNull()
            if (text != null) break
            Thread.sleep(1500)
        }
        val body = text ?: return 0
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return 0
        if (!json.optBoolean("ok")) return 0
        val resArr = json.optJSONArray("direct") ?: return 0
        var emitted = 0
        for (i in 0 until resArr.length()) {
            val item = resArr.optJSONObject(i) ?: continue
            val link = item.optString("url")
            if (link.isNotBlank() &&
                probeMedia(link, embed, ua, extra) != 0
            ) {
                emitDirect(label, link, embed, ua, callback, extra)
                emitted++
            }
        }
        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val diag = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val servers = runCatching { fetchServers(data) }.getOrDefault(emptyList())
        diag += "srv=${servers.size}"
        if (servers.isEmpty()) {
            emitDebug(diag, callback)
            return true
        }

        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link -> emitted++; callback(link) }
        val handled = HashSet<String>()
        val digQueue = ArrayList<ServerRow>()

        // Phase A (no page fetch): direct-media rows are PROBED first —
        // an .mp4 URL that answers with an HTML wrapper page is not a
        // direct at all; it goes to the dig queue like any embed. Rows on
        // extractor-known hosts go to the app's extractors.
        var fake = 0
        for (row in servers) {
            if (isDirectMedia(row.link)) {
                val verdict = probeMedia(row.link, row.referer, row.userAgent, row.extraHeaders)
                if (verdict == 0) {
                    fake++
                    digQueue += row
                } else {
                    emitDirect(
                        row.server, row.link, row.referer ?: mainUrl,
                        row.userAgent, counting, row.extraHeaders
                    )
                    handled += row.link
                }
            }
        }
        diag += "fake=$fake"
        for (row in servers) {
            if (row.link in handled || row in digQueue) continue
            if (isExtractorKnown(row.link)) {
                val ok = runCatching {
                    loadExtractor(row.link, row.referer ?: mainUrl, subtitleCallback, counting)
                }.getOrDefault(false)
                if (ok) handled += row.link else digQueue += row
            } else {
                digQueue += row
            }
        }
        diag += "ext=$emitted"

        // Phase B: phone-side dig of the rest (the embed hosts wall off
        // datacenter IPs by design; the phone is their intended client).
        // Brestj-class discipline: stop once enough playable links exist or
        // the time budget is spent; also try each row's tmp_link variant.
        // Every dug candidate is probed before it is emitted.
        val dugOk = HashSet<String>()
        var digs = 0
        for (row in digQueue) {
            if (emitted >= 6) break
            if (System.currentTimeMillis() - started > 12_000) break
            if (digs >= 5) break
            digs++
            var direct = digForDirect(row.link, row.referer, row.userAgent)
            if (direct != null &&
                probeMedia(direct, row.link, row.userAgent, row.extraHeaders) == 0
            ) direct = null
            if (direct == null && row.tmpLink != null) {
                direct = digForDirect(row.tmpLink, row.referer, row.userAgent)
                if (direct != null &&
                    probeMedia(direct, row.tmpLink, row.userAgent, row.extraHeaders) == 0
                ) direct = null
            }
            if (direct != null) {
                emitDirect(row.server, direct, row.link, row.userAgent, counting, row.extraHeaders)
                dugOk += row.link
            }
        }
        diag += "dig=$digs"

        // Phase C: relay dig for whatever is still unresolved (the relay's
        // egress passes some gates the phone may not, and vice versa).
        var relayed = 0
        for (row in digQueue) {
            if (relayed >= 3) break
            if (row.link in dugOk) continue
            if (System.currentTimeMillis() - started > 20_000) break
            relayed += resolveViaRelay(
                row.link, row.referer, row.server, row.userAgent, counting, row.extraHeaders
            )
        }
        diag += "relay=$relayed"

        if (emitted == 0) {
            emitDebug(diag, callback)
            return true
        }
        return true
    }

    /** Emits the collected stage markers as one playable debug source. */
    private suspend fun emitDebug(diag: List<String>, callback: (ExtractorLink) -> Unit) {
        runCatching {
            callback(
                newExtractorLink(
                    name,
                    "DEBUG " + diag.joinToString(" | "),
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
