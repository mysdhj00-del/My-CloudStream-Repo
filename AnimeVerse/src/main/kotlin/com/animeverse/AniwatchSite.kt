package com.animeverse

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Aniwatch / HiAnime (hianime.at) — third AnimeVerse member (v19).
 *
 * The user asked for "aniwatch" (2026-11-05 16:41) as the
 * multi-audio + multi-subtitle member: this site lists SUB and
 * DUB servers for every episode. The live site is hianime.at
 * (the old hianime.to / aniwatchtv.to stall from datacenter
 * IPs — TLS completes, then 0 bytes; hianime.at answers 200
 * plain, no Cloudflare challenge from the sandbox).
 *
 * Chain traced live 2026-11-05 (Naruto E1 + Gachiakuta E1,
 * sub AND dub):
 * - Home sections (ALL 5, 30 cards/page, ?page=N):
 *   /most-popular, /recently-updated, /top-airing,
 *   /most-favorite, /top-upcoming. /trending and
 *   /recently-added are 404 — they do not exist. Cards are
 *   SSR .flw-item blocks: img.film-poster-img, a.dynamic-name
 *   href=/<slug>-<id> (title text), poster on
 *   hianime.at/storage/media/... or cdn.anipixcdn.co.
 * - Search: GET /search?keyword=<q> — same .flw-item cards.
 *   Verified: "naruto" -> Naruto first; "gachiakuta" -> 1 card.
 * - Detail: GET /<slug>-<id> — <h2 class="film-name"> title,
 *   img.film-poster-img poster, .film-description .text plot,
 *   .anisc-info rows (Genres / Status / ...). Episodes come
 *   from GET /api/theme/episode/list/<animeId> (NOT the
 *   classic /ajax/v2/episode/... which 404s here) with
 *   X-Requested-With + Referer -> JSON {html} with a.ep-item
 *   data-id / data-number / title. Naruto id 1335 -> 220 eps;
 *   Gachiakuta id 530 -> 24 eps.
 * - Servers: GET /api/theme/episode/servers?episodeId=<id>
 *   (X-Requested-With) -> JSON {html} with .server-item
 *   data-type (sub/dub), data-server-name, data-hash — the
 *   hash is plain base64 of the embed URL, so NO per-server
 *   "sources" round trip is needed. Every episode sampled
 *   lists 4 servers: ZokoAnime sub, HD-1 sub, ZokoAnime dub,
 *   HD-1 dub.
 * - ZokoAnime (the playable server): embed
 *   https://zokoanime.video/stream/mal/<malId>/<ep>/<sub|dub>
 *   -> page carries window.__P = base64(JSON XORed with the
 *   repeating key "otaku-embed-v1") -> {src: master.m3u8 on
 *   hls.dramahot.top, subtitles: [{lang, label, src}]}.
 *   VERIFIED on both titles, sub + dub: master 200, media
 *   playlist 283 segments, audio MUXED in the variant (no
 *   #EXT-X-MEDIA renditions on the sampled episodes — the
 *   master URL plays with sound in ExoPlayer as-is). The
 *   site EN VTT is 403 from datacenter IPs — phone-side
 *   fetch, same class as Miruro's EN VTT.
 * - HD-1 (MegaPlay, same host class as GogoAnime v18):
 *   embed https://megaplay.buzz/stream/s-2/<realid>/<sub|dub>
 *   (needs Referer hianime.at) -> data-id from the player
 *   div -> GET /stream/getSources?id=<data-id>&s=bcdn
 *   (Referer + X-Requested-With) -> JSON {tracks:[EN VTT],
 *   enc} -> enc is base64url AES-256-CBC, key
 *   "i?LMTAx0Q6,:}50U" zero-padded to 32 bytes, IV
 *   "W0;27ToaUpl_P%'c" (same as GogoAnime) -> {"file":
 *   master.m3u8}. VERIFIED on Gachiakuta E1 (decrypt ->
 *   ncdn.imgnex.top master + mysubsplay EN VTT).
 * - Subtitles: site EN VTT (Zoko + MegaPlay) + the shared
 *   AnimeSubs layer (OpenSubtitles Arabic via IMDb suggest,
 *   relay GET mergesubs for AR+EN, phone-IP gtx translate
 *   fallback). Season is 1 — HiAnime lists every episode of
 *   a series on one page, it does not split seasons.
 * - v19 also adds step-level subtitle diagnostics to the
 *   DEBUG line (owed from the Overgeared v18 report, where
 *   store:- hid which step failed): eng fetch cue count
 *   (engcues) + translate result (tr).
 * - DEBUG markers in v19: loadLinks always emits one DEBUG
 *   Mux source carrying per-server outcomes + subtitle
 *   diagnostics, so an empty on-phone source list means
 *   loadLinks never ran.
 * - No coroutine imports anywhere (CI classpath law).
 */
object AniwatchSite : AnimeSite {
    override val host = "hianime.at"
    // Standing rule (user, 2026-11-05): every member publishes
    // ALL its sections, each titled "<section> — <Site>", with
    // the section's FULL contents sideways-scrolling — never a
    // truncated row, never a bare section name.
    override val sections = listOf(
        "ani:popular" to "الأكثر شهرة — Aniwatch",
        "ani:updated" to "أحدث التحديثات — Aniwatch",
        "ani:airing" to "الأعلى عرضاً — Aniwatch",
        "ani:favorite" to "الأكثر تفضيلاً — Aniwatch",
        "ani:upcoming" to "القادم — Aniwatch"
    )

    private const val mainUrl = "https://hianime.at"
    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    private fun posterHeaders(): Map<String, String> {
        val out = HashMap<String, String>()
        out["User-Agent"] = headers["User-Agent"] ?: "Mozilla/5.0"
        out["Referer"] = "$mainUrl/"
        return out
    }

    /** Plain request first, CloudflareKiller as fallback. */
    private suspend fun fetchHtml(url: String, referer: String? = null, ajax: Boolean = false): String {
        val h = when {
            ajax -> if (referer != null) ajaxHeaders + ("Referer" to referer) else ajaxHeaders
            referer != null -> headers + ("Referer" to referer)
            else -> headers
        }
        return try {
            app.get(url, headers = h).text
        } catch (e: Exception) {
            try {
                app.get(url, headers = h, interceptor = cloudflareKiller).text
            } catch (e2: Exception) {
                ""
            }
        }
    }

    private fun decodeEntities(s: String): String {
        return s.replace("&#8217;", "'").replace("&#8216;", "'")
            .replace("&#8220;", "\"").replace("&#8221;", "\"")
            .replace("&#8211;", "-").replace("&#8212;", "-")
            .replace("&#39;", "'").replace("&quot;", "\"")
            .replace("&amp;", "&").replace("&#039;", "'")
            .replace("&nbsp;", " ").trim()
    }

    private fun fix(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    /** One .flw-item card: (detail url, title, poster). */
    private fun parseCards(html: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val cardRe = Regex(
            """<div class="flw-item[^"]*"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*>.*?<a[^>]*href="([^"]+)"[^>]*class="[^"]*dynamic-name[^"]*"[^>]*>([^<]+)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in cardRe.findAll(html)) {
            val poster = fix(m.groupValues[1])
            val href = m.groupValues[2]
            val title = decodeEntities(m.groupValues[3])
            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/watch/") -> fix(href.replace("/watch/", "/"))
                href.startsWith("/") -> fix(href)
                else -> continue
            }
            if (title.isEmpty()) continue
            out.add(Triple(url, title, poster))
        }
        return out
    }

    private fun toSeriesCard(api: MainAPI, url: String, title: String, poster: String): SearchResponse {
        return api.newTvSeriesSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = posterHeaders()
        }
    }

    override suspend fun homeSection(api: MainAPI, data: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        try {
            val path = when (data) {
                "ani:popular" -> "most-popular"
                "ani:updated" -> "recently-updated"
                "ani:airing" -> "top-airing"
                "ani:favorite" -> "most-favorite"
                "ani:upcoming" -> "top-upcoming"
                else -> return out
            }
            for (page in 1..5) {
                val html = fetchHtml("$mainUrl/$path?page=$page")
                var added = 0
                for ((url, title, poster) in parseCards(html)) {
                    if (!seen.add(url)) continue
                    out.add(toSeriesCard(api, url, title, poster))
                    added++
                }
                if (added == 0) break
            }
        } catch (e: Exception) {
        }
        return out
    }

    override suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val seen = HashSet<String>()
            for (page in 1..2) {
                val html = fetchHtml("$mainUrl/search?keyword=$q&page=$page")
                var added = 0
                for ((url, title, poster) in parseCards(html)) {
                    if (!seen.add(url)) continue
                    out.add(toSeriesCard(api, url, title, poster))
                    added++
                }
                if (added == 0) break
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun animeIdFromUrl(url: String): String? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        return Regex("""-(\d+)$""").find(slug)?.groupValues?.get(1)
    }

    override suspend fun load(api: MainAPI, url: String): LoadResponse {
        // /watch/<slug>-<id> and /<slug>-<id> both land here;
        // the detail page is always /<slug>-<id>.
        val clean = url.substringBefore("?").trimEnd('/')
        val seriesUrl = if (clean.contains("/watch/")) clean.replace("/watch/", "/") else clean
        val html = fetchHtml(seriesUrl)
        val title = Regex("""<h2 class="film-name"[^>]*>([^<]+)</h2>""").find(html)?.groupValues?.get(1)
            ?.let { decodeEntities(it) }
            ?: seriesUrl.substringAfterLast("/").substringBeforeLast("-").replace('-', ' ')
        val poster = Regex("""<img[^>]*src="([^"]+)"[^>]*class="film-poster-img"""").find(html)?.groupValues?.get(1)
            ?.let { fix(it) }
            ?: Regex("""class="film-poster-img"[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)?.let { fix(it) }
        val plot = Regex("""<div class="film-description[^>]*>.*?<div class="text"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { decodeEntities(it.replace(Regex("<[^>]+>"), "")) }
        val infoBlock = Regex("""<div class="anisc-info[^>]*>(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: html
        val genres = Regex("""Genres:</span>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(infoBlock)?.groupValues?.get(1)
            ?.let { block -> Regex("""<a[^>]*>([^<]+)</a>""").findAll(block).map { decodeEntities(it.groupValues[1]) }.toList() }
            ?: emptyList()
        val statusText = Regex("""Status:</span>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(infoBlock)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

        val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
        val animeId = animeIdFromUrl(seriesUrl)
        if (animeId != null) {
            try {
                val epText = fetchHtml("$mainUrl/api/theme/episode/list/$animeId", referer = seriesUrl, ajax = true)
                val epHtml = try {
                    JSONObject(epText).optString("html")
                } catch (e: Exception) {
                    ""
                }
                val epRe = Regex(
                    """<a[^>]*class="[^"]*ep-item[^"]*"[^>]*data-number="(\d+)"[^>]*data-id="(\d+)"[^>]*>""",
                    RegexOption.DOT_MATCHES_ALL
                )
                val seenEp = HashSet<String>()
                for (m in epRe.findAll(epHtml)) {
                    val num = m.groupValues[1].toIntOrNull() ?: continue
                    val epId = m.groupValues[2]
                    if (!seenEp.add(epId)) continue
                    val epUrl = "$mainUrl/watch/${seriesUrl.substringAfterLast("/")}?ep=$epId&n=$num&t=" +
                        URLEncoder.encode(title, "UTF-8")
                    episodes.add(
                        api.newEpisode(epUrl) {
                            this.name = "الحلقة $num"
                            this.episode = num
                        }
                    )
                }
            } catch (e: Exception) {
            }
        }
        episodes.sortBy { it.episode }

        return if (episodes.size <= 1 && episodes.isNotEmpty()) {
            api.newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        } else {
            api.newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.showStatus = when {
                    statusText.contains("Completed", ignoreCase = true) ||
                        statusText.contains("Finished", ignoreCase = true) ->
                        com.lagradost.cloudstream3.ShowStatus.Completed
                    statusText.contains("Airing", ignoreCase = true) ||
                        statusText.contains("Ongoing", ignoreCase = true) ->
                        com.lagradost.cloudstream3.ShowStatus.Ongoing
                    else -> null
                }
            }
        }
    }

    // ---------- playback ----------

    private fun base64UrlDecode(s: String): ByteArray {
        val std = s.replace('-', '+').replace('_', '/')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    /** ZokoAnime window.__P payload: base64 -> XOR with the
     * repeating key "otaku-embed-v1" -> UTF-8 JSON. */
    private fun deobfuscateZoko(blob: String): JSONObject? {
        return try {
            val raw = base64UrlDecode(blob)
            val key = "otaku-embed-v1".toByteArray(Charsets.UTF_8)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
            JSONObject(String(out, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /** MegaPlay getSources "enc" -> master.m3u8 URL. Null on failure. */
    private fun decryptMegaEnc(enc: String): String? {
        return try {
            val raw = base64UrlDecode(enc)
            fun padKey(str: String, n: Int): ByteArray {
                val b = ByteArray(n)
                val src = str.toByteArray(Charsets.UTF_8)
                System.arraycopy(src, 0, b, 0, minOf(n, src.size))
                return b
            }
            val key = SecretKeySpec(padKey("i?LMTAx0Q6,:}50U", 32), "AES")
            val iv = IvParameterSpec(padKey("W0;27ToaUpl_P%'c", 16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val plain = cipher.doFinal(raw)
            JSONObject(String(plain, Charsets.UTF_8)).optString("file").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun qualityForHeight(h: Int): Int {
        return when {
            h >= 1000 -> Qualities.P1080.value
            h >= 700 -> Qualities.P720.value
            h >= 450 -> Qualities.P480.value
            h > 0 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun resolveUrl(uri: String, playlistUrl: String): String {
        if (uri.startsWith("http")) return uri
        val lastSlash = playlistUrl.lastIndexOf("/")
        val baseDir = if (lastSlash > 0) playlistUrl.substring(0, lastSlash + 1) else playlistUrl
        return baseDir + uri
    }

    /** Parse a master playlist into (variant url, height) pairs. */
    private fun parseMasterVariants(masterText: String, masterUrl: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val lines = masterText.split("\n")
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            for (j in i + 1 until lines.size) {
                val u = lines[j].trim()
                if (u.isEmpty() || u.startsWith("#")) continue
                out.add(resolveUrl(u, masterUrl) to h)
                break
            }
        }
        return out
    }

    private data class ServerEntry(val type: String, val name: String, val url: String)

    private fun parseServers(html: String): List<ServerEntry> {
        val out = ArrayList<ServerEntry>()
        val re = Regex(
            """data-type="([^"]+)"\s*\n?\s*data-server-name="([^"]+)"\s*\n?\s*data-hash="([^"]+)""""
        )
        for (m in re.findAll(html)) {
            val type = m.groupValues[1].lowercase()
            val name = m.groupValues[2].trim()
            val hash = m.groupValues[3]
            val url = try {
                String(base64UrlDecode(hash), Charsets.UTF_8).trim()
                    .replace(Regex("""[\u0000-\u001F\s]+$"""), "")
            } catch (e: Exception) {
                ""
            }
            if (url.startsWith("http")) out.add(ServerEntry(type, name, url))
        }
        return out
    }

    override suspend fun loadLinks(
        api: MainAPI,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var zokoDiag = "zoko:-"
        var megaDiag = "mega:-"
        var otherDiag = ""
        var engDiag = "eng:-"
        var araDiag = "ara:-"
        var storeDiag = "store:-"
        var trDiag = "tr:-"
        var engCuesDiag = "engcues:-"
        try {
            val epId = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1) ?: return true
            val epNum = Regex("""[?&]n=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val title = Regex("""[?&]t=([^&]+)""").find(data)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

            val serversText = fetchHtml(
                "$mainUrl/api/theme/episode/servers?episodeId=$epId",
                referer = "$mainUrl/", ajax = true
            )
            val serversHtml = try {
                JSONObject(serversText).optString("html")
            } catch (e: Exception) {
                ""
            }
            val servers = parseServers(serversHtml)
            if (servers.isEmpty()) {
                otherDiag = "other:no-servers"
            }
            var zokoOk = 0
            var megaOk = 0
            for (server in servers) {
                val isDub = server.type == "dub"
                val dubTag = if (isDub) " (مدبلج)" else ""
                when {
                    server.name.contains("Zoko", ignoreCase = true) -> {
                        try {
                            val embedHtml = fetchHtml(server.url, referer = "$mainUrl/")
                            val blob = Regex("""window\.__P\s*=\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            val payload = blob?.let { deobfuscateZoko(it) }
                            val src = payload?.optString("src")
                            if (src.isNullOrEmpty()) continue
                            val subs = payload.optJSONArray("subtitles")
                            if (subs != null) {
                                for (i in 0 until subs.length()) {
                                    val s = subs.optJSONObject(i) ?: continue
                                    val file = s.optString("src")
                                    val label = s.optString("label").ifEmpty { s.optString("lang") }
                                    if (file.isEmpty()) continue
                                    subtitleCallback(SubtitleFile(label, file))
                                    if (label.equals("English", ignoreCase = true) || s.optString("lang") == "en") {
                                        engDiag = "eng:zoko"
                                    }
                                }
                            }
                            val masterText = try {
                                app.get(
                                    src,
                                    headers = mapOf(
                                        "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                                        "Referer" to "https://zokoanime.video/"
                                    )
                                ).text
                            } catch (e: Exception) {
                                ""
                            }
                            val variants = if (masterText.contains("#EXT-X-STREAM-INF")) {
                                parseMasterVariants(masterText, src)
                            } else emptyList()
                            if (variants.isEmpty()) {
                                callback(
                                    newExtractorLink(
                                        "Aniwatch",
                                        "ZokoAnime$dubTag",
                                        src,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://zokoanime.video/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            } else {
                                for ((vUrl, h) in variants) {
                                    val label = if (h > 0) "${h}p" else "Auto"
                                    callback(
                                        newExtractorLink(
                                            "Aniwatch",
                                            "ZokoAnime $label$dubTag",
                                            vUrl,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://zokoanime.video/"
                                            this.quality = qualityForHeight(h)
                                        }
                                    )
                                }
                            }
                            zokoOk++
                        } catch (e: Exception) {
                        }
                    }
                    server.name.contains("HD-1", ignoreCase = true) ||
                        server.url.contains("megaplay.buzz") -> {
                        try {
                            val mpHtml = fetchHtml(server.url, referer = "$mainUrl/")
                            val dataId = Regex("""data-id="(\d+)"""").find(mpHtml)?.groupValues?.get(1)
                            if (dataId == null) {
                                megaDiag = "mega:no-id"
                                continue
                            }
                            val gsText = try {
                                app.get(
                                    "https://megaplay.buzz/stream/getSources?id=$dataId&s=bcdn",
                                    headers = mapOf(
                                        "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                                        "Referer" to server.url,
                                        "X-Requested-With" to "XMLHttpRequest"
                                    )
                                ).text
                            } catch (e: Exception) {
                                ""
                            }
                            if (gsText.isEmpty()) {
                                megaDiag = "mega:gs-empty"
                                continue
                            }
                            val gs = JSONObject(gsText)
                            val tracks = gs.optJSONArray("tracks")
                            if (tracks != null) {
                                for (i in 0 until tracks.length()) {
                                    val t = tracks.optJSONObject(i) ?: continue
                                    if (t.optString("kind") == "captions" && t.optString("label") == "English") {
                                        val vtt = t.optString("file")
                                        if (vtt.isNotEmpty()) {
                                            subtitleCallback(SubtitleFile("English", vtt))
                                            if (engDiag == "eng:-") engDiag = "eng:mega"
                                        }
                                    }
                                }
                            }
                            val master = gs.optString("enc").ifEmpty { null }?.let { decryptMegaEnc(it) }
                            if (master == null) {
                                megaDiag = "mega:dec-fail"
                                continue
                            }
                            val masterText = try {
                                app.get(
                                    master,
                                    headers = mapOf(
                                        "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                                        "Referer" to "https://megaplay.buzz/"
                                    )
                                ).text
                            } catch (e: Exception) {
                                ""
                            }
                            val variants = if (masterText.contains("#EXT-X-STREAM-INF")) {
                                parseMasterVariants(masterText, master)
                            } else emptyList()
                            if (variants.isEmpty()) {
                                callback(
                                    newExtractorLink(
                                        "Aniwatch",
                                        "MegaPlay$dubTag",
                                        master,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://megaplay.buzz/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            } else {
                                for ((vUrl, h) in variants) {
                                    val label = if (h > 0) "${h}p" else "Auto"
                                    callback(
                                        newExtractorLink(
                                            "Aniwatch",
                                            "MegaPlay $label$dubTag",
                                            vUrl,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://megaplay.buzz/"
                                            this.quality = qualityForHeight(h)
                                        }
                                    )
                                }
                            }
                            megaOk++
                        } catch (e: Exception) {
                            megaDiag = "mega:err"
                        }
                    }
                    else -> {
                        otherDiag = "other:${server.name}"
                    }
                }
            }
            zokoDiag = if (zokoOk > 0) "zoko:$zokoOk" else "zoko:0"
            if (megaOk > 0) megaDiag = "mega:$megaOk"

            // Subtitle layer (shared AnimeSubs): Arabic from
            // OpenSubtitles, merged AR+EN via the relay, phone-IP
            // translate fallback — same flow as GogoAnime v18,
            // plus step-level diagnostics (engcues / tr) so a
            // silent translate failure is visible next time.
            try {
                val araUrl = if (title.isNotEmpty() && epNum != null)
                    AnimeSubs.osSubFor(title, 1, epNum, "ara") else null
                araUrl?.let { subtitleCallback(SubtitleFile("العربية", it)) }
                val engOsUrl = if (title.isNotEmpty() && epNum != null)
                    AnimeSubs.osSubFor(title, 1, epNum, "eng") else null
                val araUrls = if (title.isNotEmpty() && epNum != null)
                    AnimeSubs.osSubsFor(title, 1, epNum, "ara") else emptyList()
                if (engDiag == "eng:-") engDiag = if (engOsUrl != null) "eng:os" else "eng:-"
                araDiag = "ara:${araUrls.size}"
                if (engOsUrl != null && araUrls.isNotEmpty()) {
                    val mergeUrl = StringBuilder("${AnimeSubs.relayBase}/relay/mergesubs?eng=")
                        .append(URLEncoder.encode(engOsUrl, "UTF-8"))
                    for (u in araUrls) mergeUrl.append("&ara=").append(URLEncoder.encode(u, "UTF-8"))
                    subtitleCallback(SubtitleFile("عربي + English", mergeUrl.toString()))
                    storeDiag = "merge:ok"
                } else if (engOsUrl != null) {
                    val engText = AnimeSubs.fetchSubText(engOsUrl, "", cloudflareKiller).first
                    val cues = if (engText.isNotEmpty()) AnimeSubs.parseSubCues(engText) else emptyList()
                    engCuesDiag = "engcues:${cues.size}"
                    if (cues.isNotEmpty()) {
                        // v21: Goojara v26 engine (gtx + MyMemory
                        // fallback, cached) — the old gtx-only call
                        // threw on the phone and produced nothing.
                        val res = AnimeTranslate.translateCues(cues, engOsUrl, engText)
                        if (res.arUrl != null) {
                            trDiag = if (AnimeTranslate.mmUsed) "tr:mm" else "tr:ok"
                            subtitleCallback(SubtitleFile("العربية (تلقائية)", res.arUrl))
                            res.dualUrl?.let { subtitleCallback(SubtitleFile("عربي + English", it)) }
                            storeDiag = if (res.dualUrl != null) "store:ok" else "store:ar-only"
                        } else {
                            trDiag = "tr:fail-${res.failStage ?: "gtx"}"
                            storeDiag = "store:tr-fail"
                        }
                    }
                }
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
        // DEBUG marker (standing rule): always emitted, wrapped so
        // nothing can kill it — if loadLinks runs at all, this
        // MUST appear in the source list.
        try {
            callback(
                newExtractorLink(
                    "Aniwatch",
                    "DEBUG v21 $zokoDiag $megaDiag $otherDiag $engDiag $araDiag $storeDiag $trDiag $engCuesDiag",
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
