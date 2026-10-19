package com.larozatv

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LaRosTV (larozatv.com) provider for CloudStream 3.
 *
 * The site runs a custom CMS (theme "alooytv"), verified live on
 * 2026-10-19 from plain browser-header fetches (no Cloudflare wall):
 *
 *   listings  /, /films, /seriestv, /episodestv, /category/{enc-ar} —
 *             paginated /page/{n}; cards are <article class="ay-card">
 *             (a.ay-card-media href, img poster, .ay-card-badge type
 *             marker — "فيلم"/"نسخة…" = movie, "الحلقة N" = episode
 *             post, no badge = series —, h3.ay-card-title)
 *   search    GET /search?q=
 *   movie     post /{slug}/ carries og meta and links
 *             /watch/{slug}/servers
 *   series    the listing card's flat /{slug}/ URL is a soft-404 shell;
 *             the real series page is /series/{slug} — its article
 *             cards are the LATEST season's episodes. Every season
 *             lives at /season/{slug}-season-{n} with clean episode
 *             cards of its own (probe 1..12, stop at 3 straight 404s).
 *   episode   post /{epSlug}/ links back to /series/{slug} and to its
 *             own /watch/{epSlug}/servers
 *   servers   /watch/{slug}/servers holds the full watch list as
 *             <button data-server-url="…"> inside #ayWatchServers.
 *             Census over several movies and episodes (2026-10-19):
 *             19 rows on every title — Doodstream ×9, MixDrop ×9,
 *             Share4max ×1.
 *
 * Playback: Doodstream and MixDrop go to the app's extractors first.
 * The fallback dig runs ON THE PHONE because the MixDrop direct (the
 * packed MDCore "wurl" on *.mxcontent.net) is token/IP-bound — minted
 * and played from the same IP it works; a relay-minted token is dead
 * on arrival (measured). Share4max is a JS-SPA player: no static
 * direct exists, so it only gets an extractor attempt. A DEBUG
 * pseudo-link with stage counters is emitted when nothing resolves.
 */
class LaRosTVProvider : MainAPI() {
    override var mainUrl = "https://larozatv.com"
    override var name = "LaRosTV"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val relayBase = "https://egydead-stremio.onrender.com"

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "/episodestv" to "أحدث الحلقات",
        "/films" to "أفلام",
        "/seriestv" to "مسلسلات",
        "/category/%D8%A7%D9%86%D9%85%D9%8A" to "أنمي",
        "/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%B1%D9%83%D9%8A" to "مسلسلات تركي",
        "/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A" to "أفلام أجنبي"
    )

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    private data class Card(
        val title: String,
        val href: String,
        val poster: String?,
        val badge: String?,
        val year: Int?
    )

    private fun parseCards(doc: Document): List<Card> {
        val out = ArrayList<Card>()
        for (article in doc.select("article.ay-card")) {
            val a = article.selectFirst("a.ay-card-media") ?: article.selectFirst("a[href]")
                ?: continue
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue
            val title = article.selectFirst(".ay-card-title")?.text()?.trim()
                ?: a.attr("aria-label").trim()
            if (title.isBlank()) continue
            val poster = article.selectFirst("img")?.let {
                it.attr("abs:src").ifBlank { it.attr("src") }
            }?.ifBlank { null }
            val badge = article.selectFirst(".ay-card-badge")?.text()?.trim()?.ifBlank { null }
            val year = article.selectFirst(".ay-card-meta")?.text()
                ?.let { Regex("""(19|20)\d\d""").find(it)?.value?.toIntOrNull() }
            out += Card(title, href, poster, badge, year)
        }
        return out
    }

    /** The slug of a post URL: its last non-empty path segment, verbatim. */
    private fun slugOf(url: String): String =
        url.trimEnd('/').substringAfterLast('/')

    /** A series card's flat href points at a soft-404 shell; the real page is /series/{slug}. */
    private fun seriesUrlFromSlug(slug: String): String = "$mainUrl/series/$slug"

    private fun serversUrlFromSlug(slug: String): String = "$mainUrl/watch/$slug/servers"

    private fun Card.toSearchResponse(): SearchResponse {
        val badge = badge ?: ""
        return when {
            badge.contains("فيلم") || badge.contains("نسخة") ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = this@toSearchResponse.poster
                    this@toSearchResponse.year?.let { this.year = it }
                }
            badge.contains("حلقة") ->
                // An episode post: load() reroutes it to its series.
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = this@toSearchResponse.poster
                }
            else ->
                newTvSeriesSearchResponse(title, seriesUrlFromSlug(slugOf(href)), TvType.TvSeries) {
                    this.posterUrl = this@toSearchResponse.poster
                }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl + request.data
        val url = if (page <= 1) base else "$base/page/$page"
        val doc = app.get(url, headers = browserHeaders).document
        val cards = parseCards(doc)
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/page/${page + 1}")
        }
        return newHomePageResponse(
            listOf(
                com.lagradost.cloudstream3.HomePageList(
                    request.name,
                    cards.map { it.toSearchResponse() }
                )
            ),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, headers = browserHeaders).document
        return parseCards(doc).map { it.toSearchResponse() }
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s*-\s*لاروزا تي في.*$"""), "").trim()

    private fun og(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=og:$prop]")?.attr("content")?.trim()?.ifBlank { null }

    /** Arabic-Indic digits → Latin, for episode-number parsing. */
    private fun latinDigits(s: String): String = buildString {
        for (c in s) append(if (c in '٠'..'٩') ('0' + (c - '٠')) else c)
    }

    private data class EpCard(val href: String, val title: String, val poster: String?, val number: Int?)

    private fun parseEpisodeCards(doc: Document): List<EpCard> {
        val out = ArrayList<EpCard>()
        for (article in doc.select("article.ay-card")) {
            val a = article.selectFirst("a.ay-card-media") ?: article.selectFirst("a[href]")
                ?: continue
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue
            val title = article.selectFirst(".ay-card-title")?.text()?.trim() ?: continue
            val badge = article.selectFirst(".ay-card-badge")?.text()?.trim().orEmpty()
            val numText = latinDigits(badge + " " + title)
            val number = Regex("""الحلقة\s*(\d+)""").find(numText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-e(\d+)(?:/|$)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val poster = article.selectFirst("img")?.let {
                it.attr("abs:src").ifBlank { it.attr("src") }
            }?.ifBlank { null }
            out += EpCard(href, title, poster, number)
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/series/")) return loadSeries(url)
        val doc = app.get(url, headers = browserHeaders).document
        // An episode post links back to its series: land on the series.
        val seriesLink = doc.select("a[href]").firstOrNull {
            val h = it.attr("abs:href")
            h.contains("/series/") && !h.contains("/seriestv")
        }?.attr("abs:href")
        if (seriesLink != null) return loadSeries(seriesLink)
        // A movie post (or a series-less episode post as a safety net).
        val title = cleanTitle(og(doc, "title") ?: doc.title())
        val poster = og(doc, "image")
        val plot = og(doc, "description")
        val year = Regex("""(19|20)\d\d""").find(title)?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, serversUrlFromSlug(slugOf(url))) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun loadSeries(seriesUrl: String): LoadResponse {
        val slug = slugOf(seriesUrl)
        val doc = app.get(seriesUrl, headers = browserHeaders).document
        val title = cleanTitle(og(doc, "title") ?: doc.title())
        val bySeason = LinkedHashMap<Int, MutableList<EpCard>>()
        val seen = HashSet<String>()
        fun add(season: Int, card: EpCard) {
            if (!seen.add(card.href)) return
            bySeason.getOrPut(season) { ArrayList() } += card
        }
        // Probe the season pages; 404 templates say "غير موجودة".
        var misses = 0
        var lastSeason = 0
        for (n in 1..12) {
            if (misses >= 3) break
            val res = runCatching {
                app.get("$mainUrl/season/$slug-season-$n", headers = browserHeaders)
            }.getOrNull()
            val cards = if (res == null || res.code != 200) emptyList()
                else parseEpisodeCards(res.document)
            if (cards.isEmpty()) {
                misses++
            } else {
                misses = 0
                lastSeason = n
                cards.forEach { add(n, it) }
            }
        }
        // The series page's own cards are the latest season's episodes.
        val ownCards = parseEpisodeCards(doc)
        val ownSeason = if (lastSeason > 0) lastSeason else 1
        ownCards.forEach { add(ownSeason, it) }
        if (bySeason.isEmpty()) throw ErrorLoadingException("No episodes found")
        val episodes = ArrayList<Episode>()
        var poster: String? = null
        for ((season, cards) in bySeason.toSortedMap()) {
            val sorted = cards.sortedBy { it.number ?: Int.MAX_VALUE }
            for ((i, card) in sorted.withIndex()) {
                if (poster == null) poster = card.poster
                episodes += newEpisode(serversUrlFromSlug(slugOf(card.href))) {
                    this.name = card.title
                    this.season = season
                    this.episode = card.number ?: (i + 1)
                    this.posterUrl = card.poster
                }
            }
        }
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------------
    // Playback helpers (the house set, ported from the proven providers)
    // ------------------------------------------------------------------

    private fun pageHeaders(ua: String?, referer: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua?.ifBlank { null } ?: browserUA
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        return h
    }

    private fun asciiSafe(url: String): String {
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 128 && (c.isLetterOrDigit() || c in "-._~:/?#[]@!$&'()*+,;=%")) sb.append(c)
            else sb.append('%').append(v.toString(16).uppercase().padStart(2, '0'))
        }
        return sb.toString()
    }

    private fun hostOf(url: String): String =
        Regex("""^[a-z]+://([^/]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun unescapeSlashes(text: String): String {
        var t = text
        var guard = 0
        while (t.contains("\\/") && guard < 5) {
            t = t.replace("\\/", "/")
            guard++
        }
        return t
    }

    /** All direct media URLs in the text, in order, deduped (cap 4). */
    private fun extractAllDirects(text: String): List<String> {
        val clean = unescapeSlashes(text)
        val out = LinkedHashSet<String>()
        fun add(u: String) {
            val t = u.trimEnd('\\')
            if (t.isNotBlank()) out += t
        }
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(clean).forEach {
            add(it.groupValues[1])
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").findAll(clean).forEach {
            add(it.groupValues[1])
        }
        // MixDrop's packed MDCore config: wurl = "//host/v2/xxx.mp4?…" (IP-bound token).
        Regex("""wurl\s*=\s*"(//[^"]+)"""").findAll(clean).forEach {
            add("https:" + it.groupValues[1])
        }
        Regex("""wurl\s*=\s*"(/[^"]+\.(?:m3u8|mp4)[^"]*)"""").findAll(clean).forEach {
            add(mainUrl + it.groupValues[1])
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean).forEach { add(it.value) }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean).forEach { add(it.value) }
        Regex("""["'](//[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)["']""").findAll(clean).forEach {
            add("https:" + it.groupValues[1])
        }
        return out.take(4)
    }

    // Dean Edwards packer unpacker (same proven port as Brestj/MyCima/FasselHD).
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

    /**
     * Streaming probe of a candidate direct URL: opens the connection,
     * reads AT MOST 512 bytes, disconnects. Returns 1 when the response
     * looks like media, 0 when it is provably not, -1 when undecided.
     * Guards against HTML wrapper pages served under media-looking URLs.
     */
    private fun probeMedia(
        url: String,
        referer: String?,
        ua: String?,
        timeoutMs: Int = 5000
    ): Int {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", ua?.ifBlank { null } ?: browserUA)
            conn.setRequestProperty("Accept", "*/*")
            if (!referer.isNullOrBlank()) {
                conn.setRequestProperty("Referer", asciiSafe(referer))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code == 403 || code == 405 || code == 501) -1 else 0
            } else {
                val ctype = (conn.contentType ?: "").lowercase()
                when {
                    ctype.contains("mpegurl") || ctype.contains("x-mpegurl") -> {
                        val head = conn.inputStream.bufferedReader().readText().take(64)
                        if (head.contains("#EXTM3U")) 1 else 0
                    }
                    ctype.startsWith("video/") || ctype.startsWith("audio/") ||
                        ctype.contains("octet-stream") -> 1
                    ctype.contains("html") -> 0
                    url.contains(".m3u8") -> {
                        val head = conn.inputStream.bufferedReader().readText().take(64)
                        if (head.contains("#EXTM3U")) 1 else 0
                    }
                    else -> {
                        val bytes = ByteArray(512)
                        val n = conn.inputStream.read(bytes)
                        if (n <= 0) {
                            -1
                        } else {
                            val head = String(bytes, 0, n, Charsets.ISO_8859_1)
                            when {
                                head.contains("#EXTM3U") -> 1
                                head.contains("<html", ignoreCase = true) ||
                                    head.contains("<!DOCTYPE", ignoreCase = true) -> 0
                                head.length >= 12 && head.substring(4, 8) == "ftyp" -> 1
                                head.contains("moov") || head.contains("mdat") -> 1
                                else -> -1
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            -1
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** One dug candidate awaiting its host-verdict probe. */
    private data class DugCandidate(
        val label: String,
        val url: String,
        val referer: String?
    )

    /**
     * Probes candidates in PARALLEL with ONE representative probe per
     * host: siblings on an already-judged host inherit its verdict
     * (the FasselHD v5 fix — sequential probes starved the later phases).
     */
    private fun probeCandidates(
        cands: List<DugCandidate>,
        wallMs: Long = 5000
    ): List<DugCandidate> {
        if (cands.isEmpty()) return emptyList()
        val reps = LinkedHashMap<String, DugCandidate>()
        for (c in cands) reps.putIfAbsent(hostOf(c.url), c)
        val pool = Executors.newFixedThreadPool(minOf(reps.size, 5).coerceAtLeast(1))
        try {
            val futures = reps.mapValues { (_, rep) ->
                pool.submit(Callable { probeMedia(rep.url, rep.referer, null, 4000) })
            }
            val verdictByHost = HashMap<String, Int>()
            for ((host, fut) in futures) {
                verdictByHost[host] = runCatching {
                    fut.get(maxOf(500L, wallMs - 500), TimeUnit.MILLISECONDS)
                }.getOrDefault(-1)
            }
            return cands.filter { verdictByHost[hostOf(it.url)] != 0 }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Phone-side dig for ALL direct media URLs inside an embed page:
     * plain markup scan, then packed-JS unpack + scan, then ONE chase
     * into an iframe whose src is itself a media URL (wrapper pages).
     * Referer rule: the candidate keeps the EMBED page as its referer —
     * the MixDrop mxcontent token is minted against the phone's IP, so
     * the dig must run on the phone (it does).
     */
    private suspend fun digAllForDirects(url: String, referer: String?): List<String> {
        val doc = runCatching {
            app.get(url, headers = pageHeaders(null, referer ?: mainUrl)).text
        }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        out += extractAllDirects(doc)
        for (iframe in Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""").findAll(doc)) {
            val src = iframe.groupValues[1]
            if (src.contains(".m3u8") || src.contains(".mp4")) out += src
        }
        if (out.isEmpty()) {
            for (unpacked in unpackPackedJs(doc)) {
                out += extractAllDirects(unpacked)
                if (out.isNotEmpty()) break
            }
        }
        if (out.isEmpty()) {
            val src = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                .find(doc)?.groupValues?.get(1)
            if (!src.isNullOrBlank() && !src.startsWith("about:") &&
                (src.startsWith("http") || src.startsWith("//"))
            ) {
                val abs = if (src.startsWith("//")) "https:$src" else src
                val inner = runCatching {
                    app.get(abs, headers = pageHeaders(null, url)).text
                }.getOrNull()
                if (inner != null) {
                    out += extractAllDirects(inner)
                    if (out.isEmpty()) {
                        for (unpacked in unpackPackedJs(inner)) {
                            out += extractAllDirects(unpacked)
                            if (out.isNotEmpty()) break
                        }
                    }
                }
            }
        }
        return out.take(3)
    }

    private suspend fun emitDirect(
        label: String,
        directUrl: String,
        referer: String?,
        ua: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val isHls = directUrl.contains(".m3u8")
        val headers = LinkedHashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = asciiSafe(referer)
        headers["User-Agent"] = ua?.ifBlank { null } ?: browserUA
        runCatching {
            callback(
                newExtractorLink(
                    name,
                    label,
                    directUrl,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer?.let { asciiSafe(it) } ?: ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

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

    /**
     * Last-resort escalation through the Render relay's generic resolver.
     * Only used for hosts whose directs are NOT IP/Turnstile-bound
     * (Doodstream/MixDrop are excluded by the caller: a relay-minted
     * token is dead on the phone).
     */
    private suspend fun resolveViaRelay(
        embed: String,
        referer: String?,
        label: String,
        callback: (ExtractorLink) -> Unit
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
            if (link.isNotBlank() && probeMedia(link, embed, null) != 0) {
                emitDirect(label, link, embed, null, callback)
                emitted++
            }
        }
        return emitted
    }

    private fun isExtractorKnown(host: String): Boolean =
        host.contains("dood") || host.contains("mixdrop")

    private fun displayHost(host: String): String = when {
        host.contains("dood") -> "Doodstream"
        host.contains("mixdrop") -> "MixDrop"
        host.contains("share4max") -> "Share4Max"
        else -> host
    }

    // ------------------------------------------------------------------
    // loadLinks — data is the title's /watch/{slug}/servers URL.
    //
    // Phase A: extractor-known hosts (Doodstream, MixDrop) in site
    //   order, stop-early at 6 emitted links or 14 s.
    // Phase B: phone digs for the rest and extractor failures (the
    //   MixDrop wurl is IP-bound — dug and played from the phone's IP);
    //   candidates get ONE parallel per-host probe round before emit.
    // Phase C: relay resolve, only for non-IP-bound classes
    //   (Share4max / unknown), cap 2.
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt
        var emitted = 0
        val diag = ArrayList<String>()

        val doc = runCatching {
            app.get(data, headers = browserHeaders).document
        }.getOrNull() ?: run {
            emitDebug(listOf("srv=ERR"), callback)
            return true
        }
        data class Row(val url: String, val label: String)
        val rows = ArrayList<Row>()
        val perHostCount = HashMap<String, Int>()
        for (btn in doc.select("[data-server-url]")) {
            val u = btn.attr("data-server-url").trim()
            if (!u.startsWith("http")) continue
            val host = hostOf(u)
            val n = (perHostCount[host] ?: 0) + 1
            perHostCount[host] = n
            rows += Row(u, "${displayHost(host)} $n")
        }
        diag += "srv=${rows.size}"

        // Phase A — extractors first.
        var extOk = 0
        val handled = HashSet<String>()
        for (row in rows) {
            if (emitted >= 6 || elapsed() > 14000) break
            if (!isExtractorKnown(hostOf(row.url))) continue
            val before = emitted
            runCatching {
                loadExtractor(row.url, data, subtitleCallback) { link ->
                    emitted++
                    callback(link)
                }
            }
            if (emitted > before) {
                extOk++
                handled += row.url
            }
        }
        diag += "ext=$extOk"

        // Phase B — phone digs (extractor failures + unknown hosts).
        val dug = ArrayList<DugCandidate>()
        for (row in rows) {
            if (elapsed() > 12000 || dug.size >= 6) break
            if (row.url in handled) continue
            if (isExtractorKnown(hostOf(row.url)) && extOk > 0 && emitted >= 3) continue
            val links = runCatching { digAllForDirects(row.url, data) }.getOrDefault(emptyList())
            for (link in links) dug += DugCandidate(row.label, link, row.url)
        }
        var digOk = 0
        for (cand in probeCandidates(dug)) {
            if (emitted >= 8) break
            emitDirect(cand.label, cand.url, cand.referer, null, callback)
            emitted++
            digOk++
        }
        diag += "dig=$digOk"

        // Phase C — relay resolve for the non-IP-bound remainder.
        var relayOk = 0
        if (emitted == 0) {
            for (row in rows) {
                if (relayOk >= 2 || elapsed() > 22000) break
                if (isExtractorKnown(hostOf(row.url))) continue
                if (row.url in handled) continue
                val n = runCatching {
                    resolveViaRelay(row.url, data, row.label, callback)
                }.getOrDefault(0)
                if (n > 0) {
                    relayOk++
                    emitted += n
                }
            }
        }
        diag += "relay=$relayOk"
        diag += "t=${elapsed() / 1000}s"

        if (emitted == 0) emitDebug(diag, callback)
        return true
    }
}
