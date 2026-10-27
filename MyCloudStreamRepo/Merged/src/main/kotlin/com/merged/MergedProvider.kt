package com.merged

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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Merged (مدمج) — movies + anime from foreign sites, apps and
 * torrent sites, v1 lanes = the two chains verified live 2026-10-27:
 *
 * - CINEBY (cineby.ws): /tv/{slug}/episode/{s}/{e} (XHR headers)
 *   returns JSON {success, playerSources[{name, url}]} on
 *   vidsrc-family hosts (TMDB id in embed URLs). Series pages
 *   embed .episode-item[data-season][data-episode] — sorted
 *   by (season, episode) into ONE list even though the site
 *   shows them per-season tabs. Movies at /movie/{slug}.
 *   Search: GET /search?q=. Lists: /movies, /tv (content-card).
 *   Home "Hot Right Now" = ranked text cards (no posters).
 * - YTS (yts.lu): homepage + /?query= are server-rendered
 *   movie cards (/movies/{slug}/). Movie page torrents API:
 *   ?api=torrents&mode=movie&name={title}&year={yr}&quality=all
 *   returns {hits[{title, seeds, peers, bytes, magnetUrl, hash}]}.
 *   Torrent servers are ranked by SEEDS (fast + live first).
 *
 * Subtitles (settings gear, MergedSubs): automatic, fail-soft.
 *   English = IMDb suggest -> OpenSubtitles v3 -> sync-first
 *   ranking (23.976fps BluRay/WEB UTF-8 first, v7 law).
 *   Arabic = first ara file from the same v3 listing.
 *   Both = ONE merged subtitle file served by the relay
 *   (/relay/mergesubs?eng=&ara=) so both languages show at
 *   the same time; the video itself is never touched.
 *   Any subtitle failure falls back to the available language
 *   (or nothing) — playback is never blocked.
 *
 * NOT in v1 (chains still unverified, added as they verify):
 *   9anime episode ajax, miruro player chain, OscarTV-web
 *   routes, NetMirror-web, EZTV/Lime/TPB/ext torrent lanes,
 *   dubbed-anime row, pagination (v1 rows are page 1 only).
 */
class MergedProvider : MainAPI() {
    override var mainUrl = "https://cineby.ws"
    override var name = "مدمج"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrom\u0065/130.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )
    private val relayBase = "https://egydead-stremio.onrender.com"

    override val mainPage = mainPageOf(
        "cb:https://cineby.ws/" to "الأكثر مشاهدة — Cineby",
        "cb:https://cineby.ws/movies" to "أفلام — Cineby",
        "cb:https://cineby.ws/tv" to "مسلسلات — Cineby",
        "yt:https://www13.yts.lu/" to "أفلام — YTS",
    )

    // ---------- main page ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (lane, base) = request.data.split(":", limit = 2)
        val doc = runCatching {
            app.get(base, headers = browserHeaders).document
        }.getOrNull() ?: return newHomePageResponse(emptyList(), false)
        val cards = when (lane) {
            "cb" -> if (base == "https://cineby.ws/") cinebyHotCards(doc)
                else cinebyGridCards(doc)
            "yt" -> ytsHomeCards(doc)
            else -> emptyList()
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
            false
        )
    }

    private fun cinebyHotCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (a in doc.select("a.hot-today-card")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val badge = a.selectFirst(".hot-today-badge")?.text()?.trim() ?: ""
            val title = a.selectFirst(".hot-today-title")?.text()?.trim() ?: continue
            val type = a.selectFirst(".hot-today-type")?.text()?.trim() ?: ""
            val score = a.selectFirst(".hot-today-score")?.text()?.trim() ?: ""
            val name = (if (badge.isNotBlank()) "$badge " else "") + title +
                (if (score.isNotBlank()) " $score" else "")
            out += if (type.equals("TV", ignoreCase = true))
                newTvSeriesSearchResponse(name, href, TvType.TvSeries)
            else
                newMovieSearchResponse(name, href, TvType.Movie)
        }
        return out
    }

    private fun cinebyGridCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (a in doc.select("article.content-card > a[href]")) {
            val href = a.attr("href")
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (href.isBlank() || title.isBlank()) continue
            val poster = a.selectFirst("img")?.attr("src")?.let { absUrl(it) }
            val isSeries = href.contains("/tv/")
            val r = if (isSeries)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            else
                newMovieSearchResponse(title, href, TvType.Movie)
            if (poster != null) r.posterUrl = poster
            out += r
        }
        return out
    }

    private fun ytsHomeCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href*=/movies/]")) {
            val href = a.attr("href")
            if (href.isBlank() || !seen.add(href)) continue
            val img = a.selectFirst("img")
            val title = img?.attr("alt")?.trim().orEmpty()
                .ifBlank { a.text().trim() }
            if (title.isBlank()) continue
            val r = newMovieSearchResponse(title, href, TvType.Movie)
            img?.attr("src")?.let { if (it.isNotBlank()) r.posterUrl = it }
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
                "https://cineby.ws/search?q=$q", headers = browserHeaders
            ).document
            out += cinebyGridCards(doc).take(12)
        }
        runCatching {
            val doc = app.get(
                "https://www13.yts.lu/?query=$q", headers = browserHeaders
            ).document
            out += ytsHomeCards(doc).take(12)
        }
        return out
    }

    private fun absUrl(u: String): String {
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "https://cineby.ws$u"
            else -> u
        }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("cineby.ws/tv/") -> loadCinebySeries(url)
            url.contains("cineby.ws/movie/") -> loadCinebyMovie(url)
            url.contains("yts.lu/movies/") -> loadYtsMovie(url)
            else -> null
        }
    }

    private fun subData(lane: String, realUrl: String, title: String, year: String): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return lane + "|||" + realUrl + "|||" + enc + "|||" + year
    }

    private fun parseYear(title: String): String {
        return Regex("""(19|20)\d\d""").find(title)?.value ?: ""
    }

    private suspend fun loadCinebyMovie(url: String): LoadResponse? {
        val doc = runCatching {
            app.get(url, headers = browserHeaders).document
        }.getOrNull() ?: return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim().orEmpty().ifBlank { return null }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.Movie, subData("cb", url, title, parseYear(title))) {
            if (!poster.isNullOrBlank()) this.posterUrl = poster
            if (!plot.isNullOrBlank()) this.plot = plot
        }
    }

    private suspend fun loadCinebySeries(url: String): LoadResponse? {
        val doc = runCatching {
            app.get(url, headers = browserHeaders).document
        }.getOrNull() ?: return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim().orEmpty().ifBlank { return null }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        // ONE ordered list: every .episode-item carries its season
        // + episode numbers, even though the site shows per-season tabs.
        val items = doc.select(".episode-item[data-season][data-episode]")
        val parsed = items.mapNotNull { el ->
            val s = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
            val e = el.attr("data-episode").toIntOrNull() ?: return@mapNotNull null
            val epTitle = el.selectFirst(".episode-title")?.text()?.trim()
            Triple(s, e, epTitle)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val base = url.trimEnd('/')
        val year = parseYear(title)
        val eps = parsed.map { (s, e, epTitle) ->
            val epUrl = "$base/episode/$s/$e"
            newEpisode(subData("cb", epUrl, title, year)) {
                this.name = epTitle?.ifBlank { null } ?: "S${s}E$e"
                this.season = s
                this.episode = e
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

    private suspend fun loadYtsMovie(url: String): LoadResponse? {
        val doc = runCatching {
            app.get(url, headers = browserHeaders).document
        }.getOrNull() ?: return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim().orEmpty().ifBlank { return null }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.Movie, subData("yt", url, title, parseYear(title))) {
            if (!poster.isNullOrBlank()) this.posterUrl = poster
            if (!plot.isNullOrBlank()) this.plot = plot
        }
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

    private suspend fun emitSubs(
        title: String,
        yearStr: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!MergedSubs.english && !MergedSubs.arabic) return
        val year = yearStr.toIntOrNull()
        val (eng, ara) = pickSubs(title, year)
        if (MergedSubs.english && MergedSubs.arabic) {
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
            if (eng != null) subtitleCallback(SubtitleFile("English", eng))
            else if (ara != null) subtitleCallback(SubtitleFile("العربية", ara))
            return
        }
        if (MergedSubs.english && eng != null) {
            subtitleCallback(SubtitleFile("English", eng))
        }
        if (MergedSubs.arabic && ara != null) {
            subtitleCallback(SubtitleFile("العربية", ara))
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
        val parts = data.split("|||")
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
            "cb" -> loadLinksCineby(realUrl, subtitleCallback, callback, ::elapsed)
            "yt" -> loadLinksYts(realUrl, title, year, subtitleCallback, callback, ::elapsed)
            else -> {
                emitDebug(listOf("Merged", "unknown-lane"), callback)
                true
            }
        }
    }

    private suspend fun emitDebug(diag: List<String>, callback: (ExtractorLink) -> Unit) {
        try {
            callback(
                newExtractorLink(
                    "Merged",
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

    private suspend fun loadLinksCineby(
        epUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val json = runCatching {
            app.get(
                epUrl,
                headers = browserHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            ).text
        }.getOrNull()
        val obj = runCatching { JSONObject(json ?: "") }.getOrNull()
        if (obj == null || !obj.optBoolean("success", false)) {
            emitDebug(listOf("Merged·Cb", "ep-fail"), callback)
            return true
        }
        val sources = obj.optJSONArray("playerSources")
        var srv = 0
        var ext = 0
        val seriesUrl = epUrl.substringBefore("/episode/")
        val embeds = mutableListOf<String>()
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val u = sources.optJSONObject(i)?.optString("url") ?: continue
                if (u.isNotBlank()) {
                    srv++
                    embeds += u
                }
            }
        }
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        for (embed in embeds) {
            if (emitted >= 5 || elapsed() > 12000) break
            runCatching {
                loadExtractor(embed, seriesUrl, subtitleCallback, counting)
            }
            ext = emitted
        }
        if (emitted == 0) {
            var relay = 0
            for (embed in embeds.take(2)) {
                if (elapsed() > 22000) break
                val r = runCatching {
                    app.get(
                        "$relayBase/relay/resolve?url=" +
                            java.net.URLEncoder.encode(embed, "UTF-8") +
                            "&ref=" + java.net.URLEncoder.encode(seriesUrl, "UTF-8")
                    ).text
                }.getOrNull() ?: continue
                val direct = runCatching {
                    JSONObject(r).optJSONArray("direct")
                }.getOrNull() ?: continue
                for (i in 0 until direct.length()) {
                    val d = direct.optString(i)
                    if (d.isBlank()) continue
                    relay++
                    callback(
                        newExtractorLink("Merged", "Relay", d, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
            if (relay == 0) {
                emitDebug(
                    listOf("Merged·Cb", "srv=$srv", "ext=$ext", "relay=0", "t=${elapsed() / 1000}s"),
                    callback
                )
            }
        }
        return true
    }

    private suspend fun loadLinksYts(
        movieUrl: String,
        title: String,
        year: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val apiUrl = movieUrl.trimEnd('/') + "?api=torrents&mode=movie" +
            "&name=" + java.net.URLEncoder.encode(title, "UTF-8") +
            "&year=" + java.net.URLEncoder.encode(year, "UTF-8") +
            "&quality=all"
        val json = runCatching {
            app.get(apiUrl, headers = browserHeaders).text
        }.getOrNull()
        val hits = runCatching {
            JSONObject(json ?: "").optJSONArray("hits")
        }.getOrNull()
        if (hits == null || hits.length() == 0) {
            emitDebug(listOf("Merged·Yts", "no-hits"), callback)
            return true
        }
        data class Tor(val magnet: String, val seeds: Int, val label: String, val q: Int)
        val tors = mutableListOf<Tor>()
        for (i in 0 until hits.length()) {
            val o = hits.optJSONObject(i) ?: continue
            val magnet = o.optString("magnetUrl")
            if (!magnet.startsWith("magnet:")) continue
            val t = o.optString("title")
            val seeds = o.optString("seeds").toIntOrNull() ?: 0
            val bytes = o.optString("bytes").toLongOrNull() ?: 0L
            val sizeGb = if (bytes > 0) "%.1f GB".format(bytes / 1073741824.0) else ""
            val q = when {
                t.contains("2160p") -> Qualities.P2160.value
                t.contains("1080p") -> Qualities.P1080.value
                t.contains("720p") -> Qualities.P720.value
                t.contains("480p") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            val qLabel = when {
                t.contains("2160p") -> "2160p"
                t.contains("1080p") -> "1080p"
                t.contains("720p") -> "720p"
                t.contains("480p") -> "480p"
                else -> ""
            }
            val label = listOf(qLabel, sizeGb, "$seeds seeds").filter { it.isNotBlank() }
                .joinToString(" · ")
            tors += Tor(magnet, seeds, label, q)
        }
        // Fast + live first: seeds descending, dead (0 seeds) dropped.
        val ranked = tors.filter { it.seeds > 0 }.sortedByDescending { it.seeds }.take(5)
        if (ranked.isEmpty()) {
            emitDebug(listOf("Merged·Yts", "no-live"), callback)
            return true
        }
        for (tor in ranked) {
            callback(
                newExtractorLink("YTS", tor.label, tor.magnet, ExtractorLinkType.VIDEO) {
                    this.quality = tor.q
                }
            )
        }
        return true
    }
}
