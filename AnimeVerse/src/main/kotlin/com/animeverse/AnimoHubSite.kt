package com.animeverse

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AnimoHub — Dooo-panel anime app (com.animohub.pro), second
 * AnimeVerse member. Chain traced live 2026-11-05:
 *
 * - API base is NOT the panel root: the demo Dooo v2.9.7 APK
 *   (official, github.com/kaustuv90/demo_apps) shows
 *   AppConfig.url = RC SERVER_URL + "android/", key sent as
 *   header "x-api-key". Every earlier guess (/api/..., /v1/...,
 *   index.php/...) 404'd at nginx for exactly this reason.
 *   Live on pro.apkthugs.com: android/get_config,
 *   getRandMovies, getTrending, getGenreList, getMostSearched,
 *   getRecentContentList/Movies, getAllMovies/1 all 200.
 * - Home rows (Wecima2-style: every section, full content via
 *   sideways scroll): getTrending, getRecentContentList/Movies
 *   and /WebSeries, getMostWatched/Movies/10 and /WebSeries/10,
 *   getMostSearched, getRandMovies, getRandWebSeries,
 *   getAllMovies/{page} and getAllWebSeries/{page} (20/page,
 *   rows fetch pages 1..5 = up to 100 cards). Genre/network
 *   rows are NOT shipped: getContentsReletedToGenre and
 *   getAllContentsOfNetwork answer "No Data Avaliable" on this
 *   panel — not a code gap.
 * - Search: android/searchContent/{query}/{page}, spaces -> %20.
 * - Detail: getMovieDetails/{id} / getWebSeriesDetails/{id}
 *   (single JSON objects); seasons getSeasons/{id} ("Season 1
 *   {100~150}MB", season_order); episodes
 *   getEpisodes/{seasonId}/1 (52 eps for Naruto S1).
 * - IDs COLLIDE across types: id 74 is Naruto (series) AND
 *   Hotel Transylvania 2 (movie); id 186 is Road to Ninja
 *   (movie) AND Beyblade: Metal Saga (series). Card URLs must
 *   carry the kind (movie/series) or, for mixed lists
 *   (trending/most-searched/search), the title (?t=) so load()
 *   can pick the matching detail by name.
 * - MOVIES play: getMoviePlayLinks/{id}/1 -> Embed links
 *   (quality + size, e.g. 720p 1.53GB) to
 *   play.apkthugs.com/embed/{code} -> iframe vidara.so/e/{code}
 *   -> POST /api/stream {filecode, device, codecs} ->
 *   streaming_url (+ subtitles). The /api/stream POST times out
 *   from the sandbox (datacenter IP + Cloudflare __cf_bm) — the
 *   phone runs it through CloudflareKiller, so playback is
 *   verified ON-DEVICE only, never claimed from here.
 * - SERIES DO NOT play on this panel: episode play links are
 *   "No Data Avaliable" for every sampled episode (Naruto S1
 *   E01/E25/E52, S2 E01, Chainsaw Man E01/E12, Dark Gathering,
 *   Wolf King, Batman: Caped Crusader, Cells at Work CODE
 *   BLACK). Episodes are listed (metadata only) and loadLinks
 *   labels the case "no-play-links", like Wecima2's
 *   no-servers-on-site. If the admin adds links later, the
 *   episode path already uses the same Vidara flow.
 * - Movie audio varies per title (embed page titles seen:
 *   "Multi Audio ESub", "Hindi Fandub") — the source name
 *   carries quality + size so the user sees what it is. v14:
 *   the source name also carries the audio language («هندي» /
 *   «متعدد الصوت» / «مدبلج», from the embed page title) so a
 *   Hindi-only file is visible BEFORE playing — the player can
 *   only list the audio tracks the file itself contains.
 * - Subtitles: movies use the shared AnimeSubs layer
 *   (العربية + عربي + English via relay merge, phone-IP
 *   auto-translate fallback). Episodes: moot (no video).
 * - No coroutine imports anywhere (CI classpath law).
 */
object AnimoHubSite : AnimeSite {
    override val host = "animohub/"
    override val sections = listOf(
        "animohub:trending" to "الرائج",
        "animohub:recent-movies" to "أحدث الأفلام",
        "animohub:recent-series" to "أحدث المسلسلات",
        "animohub:most-movies" to "الأكثر مشاهدة — أفلام",
        "animohub:most-series" to "الأكثر مشاهدة — مسلسلات",
        "animohub:most-searched" to "الأكثر بحثاً",
        "animohub:rand-movies" to "أفلام عشوائية",
        "animohub:rand-series" to "مسلسلات عشوائية",
        "animohub:all-movies" to "كل الأفلام",
        "animohub:all-series" to "كل المسلسلات"
    )

    private const val panelBase = "https://pro.apkthugs.com/android/"
    // The AnimoHub app's own API key, fetched from its public
    // Firebase Remote Config (the same key the app ships with and
    // the panel itself echoes in get_config). Without it the panel
    // answers 403 "Invalid API key" on every endpoint.
    private const val apiKey = "hxzble6oyLS5Gfvi"
    private const val internalBase = "https://animohub/"

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept" to "application/json",
        "x-api-key" to apiKey
    )

    private suspend fun apiText(path: String): String {
        return try {
            app.get(panelBase + path, headers = apiHeaders).text
        } catch (e: Exception) {
            ""
        }
    }

    /** JSON object or null ("No Data Avaliable" / empty / error). */
    private suspend fun apiObj(path: String): org.json.JSONObject? {
        val t = apiText(path).trim()
        if (t.isEmpty() || !t.startsWith("{")) return null
        return try {
            org.json.JSONObject(t)
        } catch (e: Exception) {
            null
        }
    }

    /** JSON array or empty list. */
    private suspend fun apiArr(path: String): org.json.JSONArray {
        val t = apiText(path).trim()
        if (t.isEmpty() || !t.startsWith("[")) return org.json.JSONArray()
        return try {
            org.json.JSONArray(t)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace("&amp;", "&").replace("&#39;", "'").trim()
    }

    private fun normName(s: String): String {
        return s.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun yearOf(date: String?): Int? {
        return date?.take(4)?.toIntOrNull()
    }

    private fun tagsOf(genres: String?): List<String> {
        return genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private fun qualityOf(q: String?): Int {
        return when {
            q == null -> Qualities.Unknown.value
            q.contains("2160") || q.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ---------- cards ----------

    private fun cardFor(api: MainAPI, o: org.json.JSONObject, kind: String): SearchResponse? {
        val id = o.optString("id").ifEmpty { return null }
        val name = cleanTitle(o.optString("name")).ifEmpty { return null }
        val poster = o.optString("poster").takeIf { it.isNotBlank() }?.let { api.fixUrl(it) }
        val url = when (kind) {
            "movie" -> "${internalBase}movie/$id"
            "series" -> "${internalBase}series/$id"
            else -> "${internalBase}item/$id?t=" + URLEncoder.encode(name, "UTF-8")
        }
        return if (kind == "series") {
            api.newTvSeriesSearchResponse(name, url, TvType.Anime) {
                this.posterUrl = poster
            }
        } else {
            api.newMovieSearchResponse(name, url, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        }
    }

    private fun cardsFrom(api: MainAPI, arr: org.json.JSONArray, kind: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val card = try {
                cardFor(api, o, kind)
            } catch (e: Exception) {
                null
            } ?: continue
            if (seen.add(card.url)) out.add(card)
        }
        return out
    }

    // ---------- home / search ----------

    override suspend fun homeSection(api: MainAPI, data: String): List<SearchResponse> {
        return when (data) {
            "animohub:trending" -> cardsFrom(api, apiArr("getTrending"), "item")
            "animohub:recent-movies" -> cardsFrom(api, apiArr("getRecentContentList/Movies"), "movie")
            "animohub:recent-series" -> cardsFrom(api, apiArr("getRecentContentList/WebSeries"), "series")
            "animohub:most-movies" -> cardsFrom(api, apiArr("getMostWatched/Movies/10"), "movie")
            "animohub:most-series" -> cardsFrom(api, apiArr("getMostWatched/WebSeries/10"), "series")
            "animohub:most-searched" -> cardsFrom(api, apiArr("getMostSearched"), "item")
            "animohub:rand-movies" -> cardsFrom(api, apiArr("getRandMovies"), "movie")
            "animohub:rand-series" -> cardsFrom(api, apiArr("getRandWebSeries"), "series")
            "animohub:all-movies", "animohub:all-series" -> {
                // Paginated rows: fetch pages until a short/empty
                // page, cap 5 pages (100 cards), like Wecima2 v9.
                val ep = if (data == "animohub:all-movies") "getAllMovies" else "getAllWebSeries"
                val kind = if (data == "animohub:all-movies") "movie" else "series"
                val out = ArrayList<SearchResponse>()
                val seen = HashSet<String>()
                for (page in 1..5) {
                    val arr = apiArr("$ep/$page")
                    if (arr.length() == 0) break
                    for (c in cardsFrom(api, arr, kind)) {
                        if (seen.add(c.url)) out.add(c)
                    }
                    if (arr.length() < 20) break
                }
                out
            }
            else -> emptyList()
        }
    }

    override suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return cardsFrom(api, apiArr("searchContent/$q/1"), "item")
    }

    // ---------- load ----------

    private suspend fun movieResponse(api: MainAPI, url: String, d: org.json.JSONObject): LoadResponse {
        val title = cleanTitle(d.optString("name"))
        val data = "${internalBase}movie/${d.optString("id")}"
        return api.newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
            this.posterUrl = d.optString("poster").takeIf { it.isNotBlank() }?.let { api.fixUrl(it) }
            this.plot = d.optString("description").takeIf { it.isNotBlank() }
            this.year = yearOf(d.optString("release_date").takeIf { it.isNotBlank() })
            this.tags = tagsOf(d.optString("genres").takeIf { it.isNotBlank() })
        }
    }

    private suspend fun seriesResponse(api: MainAPI, url: String, d: org.json.JSONObject): LoadResponse {
        val title = cleanTitle(d.optString("name"))
        val id = d.optString("id")
        val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
        val seasons = apiArr("getSeasons/$id")
        for (s in 0 until seasons.length()) {
            val so = seasons.optJSONObject(s) ?: continue
            val seasonId = so.optString("season_id").ifEmpty { so.optString("id") }
            if (seasonId.isEmpty()) continue
            val seasonNum = so.optString("season_order").toIntOrNull() ?: (s + 1)
            val eps = apiArr("getEpisodes/$seasonId/1")
            for (e in 0 until eps.length()) {
                val eo = eps.optJSONObject(e) ?: continue
                val epId = eo.optString("id")
                if (epId.isEmpty()) continue
                val rawName = eo.optString("Episoade_Name")
                val epNum = Regex("""(?i)^E(\d+)""").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: eo.optString("episoade_order").toIntOrNull()
                    ?: (e + 1)
                val rest = rawName.substringAfter(":", "").trim()
                    .ifEmpty { rawName }
                episodes.add(
                    api.newEpisode("${internalBase}episode/$epId?s=$seasonNum&e=$epNum&t=" + URLEncoder.encode(title, "UTF-8")) {
                        this.name = "الحلقة $epNum" + if (rest.isNotBlank() && rest != rawName) ": $rest" else ""
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }
        }
        return api.newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = d.optString("poster").takeIf { it.isNotBlank() }?.let { api.fixUrl(it) }
            this.plot = d.optString("description").takeIf { it.isNotBlank() }
            this.year = yearOf(d.optString("release_date").takeIf { it.isNotBlank() })
            this.tags = tagsOf(d.optString("genres").takeIf { it.isNotBlank() })
        }
    }

    override suspend fun load(api: MainAPI, url: String): LoadResponse {
        val path = url.substringAfter(internalBase)
        when {
            path.startsWith("movie/") -> {
                val id = path.substringAfter("movie/").substringBefore("?").substringBefore("/")
                val d = apiObj("getMovieDetails/$id")
                    ?: throw Error("AnimoHub: no movie $id")
                return movieResponse(api, url, d)
            }
            path.startsWith("series/") -> {
                val id = path.substringAfter("series/").substringBefore("?").substringBefore("/")
                val d = apiObj("getWebSeriesDetails/$id")
                    ?: throw Error("AnimoHub: no series $id")
                return seriesResponse(api, url, d)
            }
            path.startsWith("item/") -> {
                // Mixed lists: ids collide across types, so fetch
                // both details and pick by title match.
                val id = path.substringAfter("item/").substringBefore("?").substringBefore("/")
                val want = try {
                    URLDecoder.decode(url.substringAfter("?t=", ""), "UTF-8")
                } catch (e: Exception) {
                    ""
                }
                val wantN = normName(want)
                val movie = apiObj("getMovieDetails/$id")
                val series = apiObj("getWebSeriesDetails/$id")
                fun matches(d: org.json.JSONObject?): Boolean {
                    if (d == null) return false
                    val n = normName(d.optString("name"))
                    if (n.isEmpty() || wantN.isEmpty()) return false
                    val a = n.take(20)
                    val b = wantN.take(20)
                    return a == b || n.contains(b) || wantN.contains(a)
                }
                if (movie != null && (series == null || matches(movie))) return movieResponse(api, url, movie)
                if (series != null) return seriesResponse(api, url, series)
                if (movie != null) return movieResponse(api, url, movie)
                throw Error("AnimoHub: no item $id")
            }
            else -> throw Error("AnimoHub: unknown url $url")
        }
    }

    // ---------- playback: Vidara ----------

    /**
     * Resolves one panel Embed link (play.apkthugs.com/embed/{code})
     * to the direct stream: the embed page carries an iframe
     * vidara.so/e/{filecode}; POST {vidara}/api/stream
     * {filecode, device, codecs} returns streaming_url (+ the
     * file's own subtitles). Returns (streamUrl, subs, diag).
     * The POST is Cloudflare-gated from datacenter IPs (sandbox:
     * 20s timeout on both vidara.so and its mirror gobsao8u.com)
     * — on the phone it runs through CloudflareKiller.
     * v14: also returns the embed page title's audio hint
     * ("هندي" / "متعدد الصوت") so the source name tells the user
     * the audio language BEFORE playing — AnimoHub files are
     * often Hindi fandubs with a single audio track, and the
     * player can only list what the file contains.
     */
    private class VidaraResult(
        val url: String?,
        val subs: List<Pair<String, String>>,
        val diag: String,
        val audioHint: String
    )

    private suspend fun resolveVidara(embedUrl: String): VidaraResult {
        var diag = "vid:-"
        return try {
            val page = try {
                app.get(embedUrl, headers = mapOf("User-Agent" to (apiHeaders["User-Agent"] ?: "Mozilla/5.0"), "Referer" to "https://pro.apkthugs.com/"), interceptor = cloudflareKiller).text
            } catch (e: Exception) {
                try {
                    app.get(embedUrl, headers = mapOf("User-Agent" to (apiHeaders["User-Agent"] ?: "Mozilla/5.0"))).text
                } catch (e2: Exception) {
                    ""
                }
            }
            if (page.isEmpty()) return VidaraResult(null, emptyList(), "vid:page-empty", "")
            val embedTitle = Regex("""<title>([^<]*)</title>""").find(page)?.groupValues?.get(1) ?: ""
            val lowerTitle = embedTitle.lowercase()
            // v15: multi-audio FIRST — files like "[Hindi-Eng-Jap]"
            // or "(Hin-Eng)" carry English+Japanese too; labelling
            // them "هندي" alone (v14) hid that. Only a bare Hindi
            // file (no eng/jap in the name) is labelled "هندي".
            val audioHint = when {
                lowerTitle.contains("multi audio") || lowerTitle.contains("dual audio") -> "متعدد الصوت"
                lowerTitle.contains("hindi") && (lowerTitle.contains("eng") || lowerTitle.contains("jap")) -> "متعدد الصوت"
                lowerTitle.contains("hindi") -> "هندي"
                lowerTitle.contains("fandub") -> "مدبلج"
                else -> ""
            }
            val iframe = Regex("""src="(https://[^"]+/e/([A-Za-z0-9]+))"""").find(page)
                ?: return VidaraResult(null, emptyList(), "vid:no-iframe", audioHint)
            val iframeUrl = iframe.groupValues[1]
            val filecode = iframe.groupValues[2]
            val origin = iframeUrl.substringBefore("/e/")
            val hosts = listOf(origin, "https://gobsao8u.com").distinct()
            val body = org.json.JSONObject()
                .put("filecode", filecode)
                .put("device", "android")
                .put("codecs", org.json.JSONArray())
                .toString()
            for (host in hosts) {
                try {
                    val res = app.post(
                        "$host/api/stream",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Referer" to iframeUrl,
                            "Origin" to host,
                            "User-Agent" to (apiHeaders["User-Agent"] ?: "Mozilla/5.0")
                        ),
                        requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                        interceptor = cloudflareKiller
                    )
                    val t = res.text.trim()
                    if (t.isEmpty() || !t.startsWith("{")) {
                        diag = "vid:$host-empty"
                        continue
                    }
                    val jo = org.json.JSONObject(t)
                    val streamUrl = jo.optString("streaming_url").ifEmpty { null }
                    val subs = ArrayList<Pair<String, String>>()
                    val sarr = jo.optJSONArray("subtitles")
                    if (sarr != null) {
                        for (i in 0 until sarr.length()) {
                            val so = sarr.optJSONObject(i) ?: continue
                            val file = so.optString("file_path")
                            if (file.isBlank()) continue
                            subs.add((so.optString("language").ifEmpty { "Subtitle" }) to file)
                        }
                    }
                    if (streamUrl != null) return VidaraResult(streamUrl, subs, "vid:ok", audioHint)
                    diag = "vid:$host-nourl"
                } catch (e: Exception) {
                    diag = "vid:$host-err"
                    continue
                }
            }
            VidaraResult(null, emptyList(), diag, audioHint)
        } catch (e: Exception) {
            VidaraResult(null, emptyList(), "vid:err", "")
        }
    }

    private suspend fun emitVidaraLink(
        api: MainAPI,
        embedUrl: String,
        quality: String?,
        size: String?,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val result = resolveVidara(embedUrl)
        val streamUrl = result.url
        val subs = result.subs
        val diag = result.diag
        for ((label, file) in subs) {
            try {
                subtitleCallback(SubtitleFile(label, file))
            } catch (e: Exception) {
            }
        }
        if (streamUrl != null) {
            val label = "AnimoHub" +
                (if (!quality.isNullOrBlank()) " $quality" else "") +
                (if (!size.isNullOrBlank()) " $size" else "") +
                (if (result.audioHint.isNotBlank()) " - ${result.audioHint}" else "")
            val type = if (streamUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            try {
                callback(
                    newExtractorLink(
                        "AnimoHub",
                        label,
                        streamUrl,
                        type
                    ) {
                        this.referer = embedUrl
                        this.quality = qualityOf(quality)
                    }
                )
            } catch (e: Exception) {
            }
        }
        return diag
    }

    // ---------- playback: loadLinks ----------

    override suspend fun loadLinks(
        api: MainAPI,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linkCount = 0
        var vidDiag = "vid:-"
        var engDiag = "eng:-"
        var araDiag = "ara:-"
        var storeDiag = "store:-"
        var noLinks = false
        var title = ""
        var season = 1
        var episode: Int? = null
        try {
            val path = data.substringAfter(internalBase)
            when {
                path.startsWith("movie/") -> {
                    val id = path.substringAfter("movie/").substringBefore("?").substringBefore("/")
                    val det = apiObj("getMovieDetails/$id")
                    title = det?.optString("name") ?: ""
                    val links = apiArr("getMoviePlayLinks/$id/1")
                    for (i in 0 until links.length()) {
                        val lo = links.optJSONObject(i) ?: continue
                        val url = lo.optString("url")
                        if (url.isBlank()) continue
                        linkCount++
                        val type = lo.optString("type")
                        val quality = lo.optString("quality").takeIf { it.isNotBlank() }
                        val size = lo.optString("size").takeIf { it.isNotBlank() }
                        if (type.equals("Embed", ignoreCase = true) || url.contains("play.apkthugs.com/embed/")) {
                            vidDiag = emitVidaraLink(api, url, quality, size, callback, subtitleCallback)
                        } else if (url.contains(".m3u8") || url.contains(".mp4")) {
                            val t = if (url.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                            callback(
                                newExtractorLink("AnimoHub", "AnimoHub" + (if (!quality.isNullOrBlank()) " $quality" else ""), url, t) {
                                    this.quality = qualityOf(quality)
                                }
                            )
                        }
                    }
                    if (linkCount == 0) noLinks = true
                }
                path.startsWith("episode/") -> {
                    val id = path.substringAfter("episode/").substringBefore("?").substringBefore("/")
                    val q = data.substringAfter("?", "")
                    season = Regex("""[?&]s=(\d+)""").find("?$q")?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    episode = Regex("""[?&]e=(\d+)""").find("?$q")?.groupValues?.get(1)?.toIntOrNull()
                    title = try {
                        URLDecoder.decode(data.substringAfter("?t=", "").substringBefore("&"), "UTF-8")
                    } catch (e: Exception) {
                        ""
                    }
                    val links = apiArr("getEpisodePlayLinks/$id/1")
                    for (i in 0 until links.length()) {
                        val lo = links.optJSONObject(i) ?: continue
                        val url = lo.optString("url")
                        if (url.isBlank()) continue
                        linkCount++
                        val quality = lo.optString("quality").takeIf { it.isNotBlank() }
                        val size = lo.optString("size").takeIf { it.isNotBlank() }
                        if (url.contains("play.apkthugs.com/embed/")) {
                            vidDiag = emitVidaraLink(api, url, quality, size, callback, subtitleCallback)
                        }
                    }
                    if (linkCount == 0) noLinks = true
                }
                else -> noLinks = true
            }
        } catch (e: Exception) {
        }
        // Arabic subtitles for movies (shared layer): ready Arabic
        // + relay merge AR+EN, else phone-IP auto-translate. Only
        // when a video exists to subtitle — episodes have none.
        if (title.isNotEmpty() && !noLinks) {
            try {
                val araUrl = AnimeSubs.osSubFor(title, season, episode, "ara")
                araUrl?.let { subtitleCallback(SubtitleFile("العربية", it)) }
            } catch (e: Exception) {
            }
            try {
                val engOsUrl = AnimeSubs.osSubFor(title, season, episode, "eng")
                val araUrls = AnimeSubs.osSubsFor(title, season, episode, "ara")
                engDiag = if (engOsUrl != null) "eng:os" else "eng:-"
                araDiag = "ara:${araUrls.size}"
                if (engOsUrl != null && araUrls.isNotEmpty()) {
                    val mergeUrl = StringBuilder("${AnimeSubs.relayBase}/relay/mergesubs?eng=")
                        .append(URLEncoder.encode(engOsUrl, "UTF-8"))
                    for (u in araUrls) mergeUrl.append("&ara=").append(URLEncoder.encode(u, "UTF-8"))
                    subtitleCallback(SubtitleFile("عربي + English", mergeUrl.toString()))
                    storeDiag = "merge:ok"
                } else if (engOsUrl != null) {
                    val engText = AnimeSubs.fetchSubText(engOsUrl, "https://pro.apkthugs.com/", cloudflareKiller).first
                    engDiag = "eng:${AnimeSubs.parseSubCues(engText).size}cue(os)"
                    if (engText.isNotEmpty()) {
                        val cues = AnimeSubs.parseSubCues(engText)
                        val tr = AnimeSubs.translateSubCues(cues)
                        if (tr != null && cues.isNotEmpty()) {
                            val arVtt = AnimeSubs.buildSubVTT(cues, tr)
                            val storedAr = AnimeSubs.storeSub(arVtt)
                            storedAr?.let { subtitleCallback(SubtitleFile("العربية (تلقائية)", it)) }
                            val storedMerged = AnimeSubs.storeSub(AnimeSubs.mergeSubVTT(engText, arVtt))
                            storedMerged?.let { subtitleCallback(SubtitleFile("عربي + English", it)) }
                            storeDiag = if (storedAr != null || storedMerged != null) "store:ok" else "store:fail"
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        // DEBUG source, always: an empty on-phone source list
        // means loadLinks never ran; the counts say why.
        try {
            val name = if (noLinks) "DEBUG v15 no-play-links"
            else "DEBUG v15 ah:$linkCount $vidDiag $engDiag $araDiag $storeDiag"
            callback(
                newExtractorLink(
                    "AnimoHub",
                    name,
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
        }
        return true
    }
}
