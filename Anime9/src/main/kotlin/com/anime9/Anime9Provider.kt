package com.anime9

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Anime9 (أنمي 9) v1 — STANDALONE 9anime extension.
 * No relation to Merged or any other extension: only the
 * 9anime.or.at chain lives here (user order 2026-10-27: one
 * extension per site, after the merged build failed on-device).
 *
 * Chain (verified live 2026-10-27, same as Merged v7):
 * - Home/search grid: .film_list-grid .flw-item.
 * - Anime page -> .block_area-episodes[data-series] sid ->
 *   GET /wp-json/9animetv/v1/episodes/{sid}?active=0 ->
 *   JSON pages with .ep-item links (ONE ordered list).
 * - Episode page inline tsEp {episodeId} ->
 *   GET /ajax/episode/servers/?id={epId} (Referer = episode
 *   page) -> .server-item[data-embed] base64 play URL ->
 *   /stream/{hash} DIRECT mp4. SUB/DUB by data-type.
 * - Links come in pairs: Direct with the full browser header
 *   profile, plus a relay-proxied copy (/relay/hls) — the
 *   phone's bare /stream/ request is denied while the same
 *   URL plays fine in a full browser context.
 *
 * Subtitles (settings gear, Anime9Subs): automatic, fail-soft.
 *   English = IMDb suggest -> OpenSubtitles v3 -> sync-first
 *   ranking (23.976fps BluRay/WEB UTF-8 first).
 *   Arabic = first ara file from the same v3 listing.
 *   Both switches ON = ONE merged subtitle file served by
 *   the relay (/relay/mergesubs) so both languages show at
 *   the same time; the video itself is never touched.
 *   NEW (user suggestion 2026-10-27): when no Arabic file
 *   exists but English does, Arabic is produced by machine
 *   translation of the English file via the relay's new
 *   /relay/translate route, labeled "مترجمة آلياً" so its
 *   quality is never overstated. Any subtitle failure falls
 *   back to the available language (or nothing) — playback
 *   is never blocked.
 */
class Anime9Provider : MainAPI() {
    override var mainUrl = "https://9anime.or.at"
    override var name = "أنمي 9"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrom\u0065/130.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )
    private val relayBase = "https://egydead-stremio.onrender.com"

    /** Full browser header profile for media links (matches the relay's
     * proven BROWSER_HEADERS set: UA+Referer alone got 403 from a CDN
     * in the 2026-10-18 vidtest probe; the full set got 200). */
    private val streamHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
        "sec-ch-ua" to "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\", \"Not-A.Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Accept" to "*/*",
        "Accept-Language" to "ar-EG,ar;q=0.9,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "https://9anime.or.at/" to "أنمي — 9anime",
    )

    // ---------- main page ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = runCatching {
            app.get(request.data, headers = browserHeaders).document
        }.getOrNull() ?: return newHomePageResponse(emptyList(), false)
        return newHomePageResponse(
            listOf(HomePageList(request.name, anime9Cards(doc), isHorizontalImages = true)),
            false
        )
    }

    private fun anime9Cards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (item in doc.select(".film_list-grid .flw-item")) {
            val a = item.selectFirst(".film-name a")
                ?: item.selectFirst("a.film-poster-ahref") ?: continue
            val href = a.attr("href")
            if (href.isBlank() || !seen.add(href)) continue
            val title = item.selectFirst(".film-name")?.text()?.trim()
                ?.ifBlank { a.attr("title").trim() }?.ifBlank { a.text().trim() }
                ?: continue
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }
            val r = newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            if (!poster.isNullOrBlank()) r.posterUrl = poster
            out += r
        }
        return out
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        runCatching {
            val doc = app.get(
                "https://9anime.or.at/?s=$q", headers = browserHeaders
            ).document
            out += anime9Cards(doc).take(12)
        }
        return out
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("9anime.or.at/anime/") -> loadAnime9(url)
            else -> null
        }
    }

    private fun subData(lane: String, realUrl: String, title: String, year: String, extra: String = ""): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return lane + "|||" + realUrl + "|||" + enc + "|||" + year + "|||" + extra
    }

    private fun parseYear(title: String): String {
        return Regex("""(19|20)\d\d""").find(title)?.value ?: ""
    }

    private suspend fun loadAnime9(url: String): LoadResponse? {
        // Anime page -> .block_area-episodes[data-series] sid ->
        // GET /wp-json/9animetv/v1/episodes/{sid}?active=0 ->
        // JSON {status, total, pages} with .ep-item links.
        // Verified live 2026-10-27 (sid 3508 -> 4 episodes).
        val doc = runCatching {
            app.get(url, headers = browserHeaders).document
        }.getOrNull() ?: return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim().orEmpty().ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val sid = doc.selectFirst(".block_area-episodes")?.attr("data-series")
            ?.trim().orEmpty()
        if (sid.isBlank()) return null
        val json = runCatching {
            app.get(
                "https://9anime.or.at/wp-json/9animetv/v1/episodes/$sid?active=0",
                headers = browserHeaders
            ).text
        }.getOrNull() ?: return null
        val pages = runCatching { JSONObject(json).optString("pages") }
            .getOrNull().orEmpty()
        if (pages.isBlank()) return null
        val epDoc = Jsoup.parse(pages)
        val seen = HashSet<Int>()
        val eps = epDoc.select("a[href*=-episode-]").mapNotNull { a ->
            val href = a.attr("href")
            val n = Regex("""-episode-(\d+)-""").find(href)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (!seen.add(n)) return@mapNotNull null
            Pair(n, href)
        }.sortedBy { it.first }.map { (n, href) ->
            newEpisode(subData("an", href, title, "")) {
                this.name = "الحلقة $n"
                this.season = 1
                this.episode = n
                if (!poster.isNullOrBlank()) this.posterUrl = poster
            }
        }
        if (eps.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            if (!poster.isNullOrBlank()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
            }
        }
    }

    /** Pure-Kotlin base64 decode (no android/java base64 dependency,
     * so the local stub build and the real Gradle build agree). */
    private fun decodeBase64(input: String): String? {
        return runCatching {
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val clean = input.filter { it in table || it == '=' }
            val out = mutableListOf<Byte>()
            var i = 0
            while (i + 4 <= clean.length) {
                val c0 = table.indexOf(clean[i])
                val c1 = table.indexOf(clean[i + 1])
                if (c0 < 0 || c1 < 0) break
                val c2 = if (clean[i + 2] == '=') -1 else table.indexOf(clean[i + 2])
                val c3 = if (clean[i + 3] == '=') -1 else table.indexOf(clean[i + 3])
                out += ((c0 shl 2) or (c1 shr 4)).toByte()
                if (c2 >= 0) out += (((c1 and 15) shl 4) or (c2 shr 2)).toByte()
                if (c3 >= 0) out += (((c2 and 3) shl 6) or c3).toByte()
                i += 4
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Pure-Kotlin base64url encode (no padding), matching the relay's
     * b64e: same alphabet the /relay/hls route decodes with b64d. */
    private fun encodeBase64Url(input: String): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(table[b0 shr 2])
            out.append(table[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) out.append(table[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) out.append(table[b2 and 63])
            i += 3
        }
        return out.toString()
    }

    private suspend fun loadLinksAnime9(
        epUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        // Episode page inline tsEp {episodeId} ->
        // GET /ajax/episode/servers/?id={epId} (Referer = episode
        // page) -> {status, html} with .server-item[data-embed]
        // (base64 play URL) -> /play/{hash} page carries a DIRECT
        // mp4 at /stream/{hash}. No extractor, no torrent engine.
        // Verified live 2026-10-27 (ep 3534 -> my.1anime.site).
        val page = runCatching {
            app.get(epUrl, headers = browserHeaders).text
        }.getOrNull()
        val epId = page?.let {
            Regex("""episodeId\s*:\s*(\d+)""").find(it)?.groupValues?.get(1)
        }.orEmpty()
        if (epId.isBlank()) {
            emitDebug(listOf("Anime9·An", "no-epid"), callback)
            return true
        }
        val json = runCatching {
            app.get(
                "https://9anime.or.at/ajax/episode/servers/?id=$epId",
                headers = browserHeaders + mapOf("Referer" to epUrl)
            ).text
        }.getOrNull()
        val html = runCatching { JSONObject(json ?: "").optString("html") }
            .getOrNull().orEmpty()
        var srv = 0
        var emitted = 0
        if (html.isNotBlank()) {
            val sDoc = Jsoup.parse(html)
            for (item in sDoc.select(".server-item[data-embed]")) {
                val emb64 = item.attr("data-embed")
                if (emb64.isBlank()) continue
                srv++
                val play = decodeBase64(emb64) ?: continue
                if (!play.contains("/play/")) continue
                val stream = play.replace("/play/", "/stream/")
                val typ = item.attr("data-type").ifBlank { "sub" }.uppercase()
                // v7 lesson: the phone's bare request is denied
                // (browser context plays fine, 00:18/23:30 verified
                // 2026-10-27). Emit the direct link WITH the full
                // browser header profile, plus a relay-proxied copy
                // (relay fetches with the same full profile and
                // pipes the bytes) as the fallback link.
                callback(
                    newExtractorLink("9anime", "$typ · Direct", stream, ExtractorLinkType.VIDEO) {
                        this.referer = play
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
                emitted++
                val relayUrl = "$relayBase/relay/hls?u=${encodeBase64Url(stream)}" +
                    "&r=${encodeBase64Url(play)}"
                callback(
                    newExtractorLink("9anime", "$typ · Relay", relayUrl, ExtractorLinkType.VIDEO) {
                        this.referer = play
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted++
            }
        }
        if (emitted == 0) {
            emitDebug(
                listOf("Anime9·An", "srv=$srv", "t=${elapsed() / 1000}s"),
                callback
            )
        }
        return true
    }

    // ---------- subtitles (automatic, gear-controlled) ----------

    private fun httpGetText(url: String, timeoutMs: Int): String? {
        return runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
            )
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val code = conn.responseCode
            if (code !in 200..299) return null
            val stream = if (conn.contentEncoding == "gzip")
                java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader(Charsets.UTF_8).readText()
        }.getOrNull()
    }

    private fun latinTitleCandidates(title: String): List<String> {
        var t = title.replace(Regex("""[\u0600-\u06FF]+"""), " ")
        t = t.replace(Regex("""(?i)\b(mtarjm|mudblj|online|web-dl|bluray|hd|hdts|hdcam|webdl)\b"""), " ")
        t = t.replace(Regex("""[^A-Za-z0-9 :'\\-!.,]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.replace(Regex("""\s+(19|20)\d\d\s*$"""), "").trim()
        if (t.isBlank()) return emptyList()
        val out = mutableListOf(t)
        Regex("""\s+\d{1,2}$""").find(t)?.let {
            val stripped = t.substring(0, it.range.first).trim()
            if (stripped.isNotBlank()) out += stripped
        }
        return out
    }

    private fun resolveImdbId(latinTitle: String, year: Int?): String? {
        val bucket = latinTitle.firstOrNull()?.lowercaseChar() ?: return null
        val q = java.net.URLEncoder.encode(latinTitle, "UTF-8")
        val json = httpGetText(
            "https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json", 8000
        ) ?: return null
        val arr = runCatching {
            JSONObject(json).optJSONArray("d")
        }.getOrNull() ?: return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!id.startsWith("tt")) continue
            val qkind = o.optString("q")
            val y = o.optInt("y", -1)
            if (fallback == null) fallback = id
            if (year != null && y != year) continue
            if (qkind == "feature" || qkind == "TV series") return id
        }
        return fallback
    }

    private data class SubHit(val url: String, val score: Int)

    private fun pickSubs(title: String, year: Int?): Pair<String?, String?> {
        var eng: String? = null
        var ara: String? = null
        runCatching {
            for (candidate in latinTitleCandidates(title)) {
                val tt = resolveImdbId(candidate, year) ?: continue
                val json = httpGetText(
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json",
                    8000
                ) ?: continue
                val arr = runCatching {
                    JSONObject(json).optJSONArray("subtitles")
                }.getOrNull() ?: continue
                var bestEng: SubHit? = null
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url")
                    if (u.isBlank()) continue
                    when (o.optString("lang")) {
                        "eng" -> {
                            var score = 0
                            if (o.optInt("fpsMilli", 0) == 23976) score += 3
                            val fmt = o.optString("releaseFormat")
                            if (fmt == "BluRay" || fmt == "WEB") score += 2
                            if (o.optString("SubEncoding") == "UTF-8") score += 1
                            if (bestEng == null || score > bestEng.score) {
                                bestEng = SubHit(u, score)
                            }
                        }
                        "ara" -> if (ara == null) ara = u
                    }
                }
                eng = bestEng?.url
                if (eng != null || ara != null) return@runCatching
            }
        }
        return Pair(eng, ara)
    }

    /** Machine-translated Arabic subtitle URL for an English file:
     * the relay fetches the English SRT, translates its cues to
     * Arabic, and serves the result. Lazy: translation happens
     * only when the user picks this track, never at load time. */
    private fun translatedArabicUrl(eng: String): String {
        val eu = java.net.URLEncoder.encode(eng, "UTF-8")
        return "$relayBase/relay/translate?url=$eu"
    }

    private suspend fun emitSubs(
        title: String,
        yearStr: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!Anime9Subs.english && !Anime9Subs.arabic) return
        val year = yearStr.toIntOrNull()
        val (eng, ara) = pickSubs(title, year)
        // Fallback for the common case (user note 2026-10-27):
        // Arabic file missing while English is plentiful.
        val araT = if (ara == null && eng != null) translatedArabicUrl(eng) else null
        if (Anime9Subs.english && Anime9Subs.arabic) {
            if (eng != null && ara != null) {
                val merged = runCatching {
                    val eu = java.net.URLEncoder.encode(eng, "UTF-8")
                    val au = java.net.URLEncoder.encode(ara, "UTF-8")
                    val probe = httpGetText(
                        "$relayBase/relay/mergesubs?eng=$eu&ara=$au", 15000
                    )
                    if (!probe.isNullOrBlank()) "$relayBase/relay/mergesubs?eng=$eu&ara=$au" else null
                }.getOrNull()
                if (merged != null) {
                    subtitleCallback(SubtitleFile("English + العربية", merged))
                    return
                }
            }
            if (eng != null && araT != null) {
                // Both together, Arabic half machine-translated:
                // merge the English file with the translated one.
                // Longer probe: translation itself takes seconds.
                val mergedT = runCatching {
                    val eu = java.net.URLEncoder.encode(eng, "UTF-8")
                    val au = java.net.URLEncoder.encode(araT, "UTF-8")
                    val probe = httpGetText(
                        "$relayBase/relay/mergesubs?eng=$eu&ara=$au", 30000
                    )
                    if (!probe.isNullOrBlank()) "$relayBase/relay/mergesubs?eng=$eu&ara=$au" else null
                }.getOrNull()
                if (mergedT != null) {
                    subtitleCallback(SubtitleFile("English + العربية (مترجمة آلياً)", mergedT))
                    return
                }
                // Merge probe failed: still offer both tracks
                // individually rather than nothing.
                subtitleCallback(SubtitleFile("English", eng))
                subtitleCallback(SubtitleFile("العربية (مترجمة آلياً)", araT))
                return
            }
            if (eng != null) subtitleCallback(SubtitleFile("English", eng))
            else if (ara != null) subtitleCallback(SubtitleFile("العربية", ara))
            return
        }
        if (Anime9Subs.english && eng != null) {
            subtitleCallback(SubtitleFile("English", eng))
        }
        if (Anime9Subs.arabic) {
            if (ara != null) subtitleCallback(SubtitleFile("العربية", ara))
            else if (araT != null) subtitleCallback(SubtitleFile("العربية (مترجمة آلياً)", araT))
        }
    }

    // ---------- playback ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        // Normal path: our "lane|||url|||title|||year" data. The app
        // may hand it back URI-encoded, so try the raw split first,
        // then a URL-decoded split.
        var parts = data.split("|||")
        if (parts.size < 2) {
            val decoded = runCatching {
                java.net.URLDecoder.decode(data, "UTF-8")
            }.getOrNull()
            if (decoded != null) parts = decoded.split("|||")
        }
        if (parts.size >= 2) {
            val lane = parts.getOrNull(0) ?: ""
            val realUrl = parts.getOrNull(1) ?: data
            val title = parts.getOrNull(2)?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("")
            }.orEmpty()
            val year = parts.getOrNull(3) ?: ""
            if (title.isNotBlank()) {
                emitSubs(title, year, subtitleCallback)
            }
            return when (lane) {
                "an" -> loadLinksAnime9(realUrl, subtitleCallback, callback, ::elapsed)
                else -> {
                    emitDebug(listOf("Anime9", "unknown-lane"), callback)
                    true
                }
            }
        }
        // Fallback: a plain URL arrived instead of our data string.
        return when {
            data.contains("9anime.or.at") && data.contains("-episode-") ->
                loadLinksAnime9(data, subtitleCallback, callback, ::elapsed)
            else -> {
                emitDebug(listOf("Anime9", "unknown-lane"), callback)
                true
            }
        }
    }

    private suspend fun emitDebug(diag: List<String>, callback: (ExtractorLink) -> Unit) {
        try {
            callback(
                newExtractorLink(
                    "Anime9",
                    "DEBUG " + diag.joinToString(" | "),
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
        }
    }

}
