package com.fivetv

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
import com.lagradost.cloudstream3.network.CloudflareKiller
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
 * FiveTV (فايف تي في) v1 — CATALOG-FIRST.
 *
 * Site: https://new61.5tv.lol — Arabic RTL dark UI, movies + TV series,
 * heavy Asian drama (Korean, Chinese, Japanese, Thai, Taiwanese,
 * Turkish) + foreign movies/series. No live TV, no explicit anime
 * section. No login needed for browsing or old films.
 *
 * URL model (verified live in a real browser, 2026-11-08):
 * - Lists: /latest-episodes/ (56,014 episodes, 1,167 pages),
 *   /latest-additions/, /latest-movies/.
 * - Movie detail: /movie/<slug>-<year>/ (e.g. Batman Begins 2005).
 *   Shows poster + backdrop, Arabic+English title, year, duration
 *   (2h 20m), quality HD 1080p, language, country, genres, a full
 *   English synopsis and a "مترجم" badge. NO numeric/IMDb rating.
 * - Series detail: /series/<slug>/ (e.g. العاصفة الصامتة).
 * - Episode: /episode/<slug>-<n>/ — the episode page lists its
 *   sibling episodes as numbered buttons ("باقي حلقات الدراما").
 * - Search: /search/?q=<query> (Batman -> 1 result).
 * - Discover (/discover/) is a FILTER HUB with no cards itself, so
 *   it is NOT a home section; some of its category links 404.
 *
 * PLAYBACK — NOT VERIFIED, and v1 does not pretend otherwise:
 * - There is NO separate watch page: the player is an anchor
 *   (#watch) on the detail page, with 4 server buttons that swap
 *   one iframe: server 1 = 71stream.one, server 2 = ult4vid.one,
 *   server 3 = HGCloud, server 4 = VidMoly. HGCloud + VidMoly have
 *   stock CloudStream extractors; 71stream/ult4vid are unknown.
 * - The FULL iframe srcs were NEVER captured (the agent browser
 *   only saw the domains), servers 3-4 were never clicked, and the
 *   embeds refused connection in the agent browser — NO video
 *   played anywhere during the study. The phone test IS the trace:
 *   the DEBUG line reports srv=/ext=/dig=/relay= + the embed hosts,
 *   so the first on-device run tells us what the page really holds.
 * - v1 extracts embeds three ways (player iframes, data-* attrs on
 *   server buttons, raw-HTML scan for the four documented hosts +
 *   generic /e/ /embed/ URLs) and runs the shared chain:
 *   stock extractors first, then packed-JS dig, then relay.
 *
 * THE GATE (same wall as Cima4y):
 * - Cloudflare Turnstile loops/resets even for a real browser
 *   (verify -> fresh unchecked checkbox, new Ray ID each time;
 *   /latest-movies/ never cleared after 5 attempts). Sandbox curl
 *   gets 403 "Just a moment..." on every page. The mirror domain
 *   rotates (new61 today) — the base URL is ONE constant so a move
 *   is a one-line change.
 * - PANEL #8 (2026-11-08, duck.ai, 3 answered / 3 refused) — the
 *   provider-level prescription, implemented in v1:
 *   (a) ONE consistent identity everywhere: desktop Chrome 128 on
 *       Windows 10 UA + Sec-CH-UA headers on EVERY OkHttp request.
 *       (The killer's own WebView UA is app-level, out of provider
 *       scope — the provider sets the desktop UA on its own
 *       requests; it cannot change the WebView's UA.)
 *   (b) Homepage warm-up: the homepage is fetched first (via the
 *       killer) before any catalog fetch, with ONE 5 s backoff
 *       retry if the first fetch still shows the challenge page.
 *       No 30-60 s passive wait: inside a provider the solve is
 *       driven by the killer, not by waiting.
 *   (c) CloudflareKiller interceptor on ALL fetches (catalog,
 *       search, detail, watch, embeds).
 *   What is NOT provider-level (Custom Tabs, TLS fingerprinting,
 *   GeckoView, a persistent real-browser profile) is app-level
 *   and is NOT claimed here.
 *
 * VIP GATING: new episodes may show a VIP countdown instead of a
 * player ("المتابعة المبكرة لهذه الحلقة لأعضاء VIP فقط حاليًا").
 * That is handled gracefully: no player = no links, DEBUG "vip",
 * never a crash. Old films/episodes are free.
 *
 * UNVERIFIED ASSUMPTIONS (phone test decides, flagged honestly):
 * - List pagination "?page=N" was never reported by the study
 *   browser — the phone test (scroll a lane to its end) confirms
 *   or refutes it. hasNext is capped by numbered pagination links
 *   when present, else by the lane cap (ep: 1,167 documented).
 * - Card/series/episode selectors are GENERIC and anchor-based on
 *   the documented URL model (/movie/, /series/, /episode/) — no
 *   CSS class names were invented. Season is always 1 in v1 (the
 *   study saw numbered episodes only, no season marker).
 * - No subtitle gear in v1 (a later version can port the shared
 *   auto-English gear once playback is proven).
 */
class FiveTVProvider : MainAPI() {
    override var mainUrl = "https://new61.5tv.lol"
    override var name = "فايف تي في"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true

    // Panel #8: ONE identity everywhere (desktop Chrome 128 / Win10).
    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "sec-ch-ua" to "\"Chromium\";v=\"128\", \"Google Chrome\";v=\"128\", \"Not-A.Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )
    private val relayBase = "https://egydead-stremio.onrender.com"

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "ep:$mainUrl/latest-episodes/" to "أحدث الحلقات — فايف تي في",
        "add:$mainUrl/latest-additions/" to "أحدث الإضافات — فايف تي في",
        "mov:$mainUrl/latest-movies/" to "أحدث الأفلام — فايف تي في"
    )

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    private fun cleanHtml(s: String): String =
        runCatching { Jsoup.parse(s).text() }.getOrDefault(s).trim()

    private fun cleanTitle(raw: String): String {
        var t = cleanHtml(raw).trim()
            .removePrefix("مشاهدة").trim()
            .removePrefix("فيلم").trim()
            .removePrefix("مسلسل").trim()
        t = t.replace(Regex("""\s*(مترجم|مترجمة)\s*$"""), "").trim()
        t = t.replace(Regex("""\s*[-|]\s*(فايف تي في|5TV|5tv).*$"""), "").trim()
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t
    }

    private fun resolveUrl(u: String): String {
        val t = u.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> mainUrl + t
            t.isBlank() -> t
            else -> "$mainUrl/$t"
        }
    }

    private fun asciiSafe(url: String): String {
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 128 && (c.isLetterOrDigit() || c in "-._~:/?#[]@!$&'()*+,;=%")) {
                sb.append(c)
            } else {
                sb.append('%').append(v.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    private fun hostOf(url: String): String =
        Regex("""^[a-z]+://([^/]+)""").find(url)?.groupValues?.get(1)
            ?.lowercase() ?: ""

    private fun og(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=og:$prop]")?.attr("content")
            ?.trim()?.ifBlank { null }

    private fun isChallenge(text: String): Boolean {
        return text.contains("Just a moment") ||
            text.contains("التحقق من أنك إنسان") ||
            text.contains("Attention Required") ||
            text.contains("cf_chl")
    }

    @Volatile
    private var warmed = false

    /** Panel #8 warm-up: homepage first (via the killer) before any
     * catalog fetch, with one 5 s backoff retry if the first fetch
     * still shows the challenge page. */
    private suspend fun warmUp() {
        if (warmed) return
        warmed = true
        runCatching {
            var text = runCatching {
                app.get(
                    "$mainUrl/", headers = browserHeaders,
                    interceptor = cloudflareKiller
                ).text
            }.getOrNull().orEmpty()
            if (isChallenge(text)) {
                Thread.sleep(5000)
                runCatching {
                    app.get(
                        "$mainUrl/", headers = browserHeaders,
                        interceptor = cloudflareKiller
                    ).text
                }
            }
        }
    }

    /** All fetches go through here: warm-up + killer + one 5 s
     * backoff retry when the page still looks like a challenge. */
    private suspend fun getDoc(url: String, referer: String? = null): Document? {
        warmUp()
        val h = if (referer.isNullOrBlank()) browserHeaders
        else browserHeaders + ("Referer" to asciiSafe(referer))
        val first = runCatching {
            app.get(url, headers = h, interceptor = cloudflareKiller).document
        }.getOrNull() ?: return null
        if (isChallenge(first.html())) {
            Thread.sleep(5000)
            val second = runCatching {
                app.get(url, headers = h, interceptor = cloudflareKiller).document
            }.getOrNull() ?: return null
            if (isChallenge(second.html())) return null
            return second
        }
        return first
    }

    // ---------------------------------------------------------------
    // Home
    // ---------------------------------------------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (lane, base) = request.data.split(":", limit = 2)
        // Pagination "?page=N" is UNVERIFIED (see KDoc) — the phone
        // test confirms or refutes it.
        val url = if (page <= 1) base else base + "?page=$page"
        val doc = getDoc(url) ?: return newHomePageResponse(emptyList(), false)
        val cards = parseCards(doc)
        val maxPage = doc.select("a[href]").mapNotNull { a ->
            if (a.text().trim().toIntOrNull() == null) return@mapNotNull null
            val href = a.attr("href")
            (Regex("""[?&]page=(\d+)""").find(href)?.groupValues?.get(1)
                ?: Regex("""/page/(\d+)/""").find(href)?.groupValues?.get(1))
                ?.toIntOrNull()
        }.maxOrNull()
        val laneCap = when (lane) {
            "ep" -> 1167 // documented: /latest-episodes/ = 1,167 pages
            else -> 100
        }
        val cap = maxPage ?: laneCap
        val hasNext = cards.isNotEmpty() && page < cap
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
            hasNext
        )
    }

    /** Generic card scan: anchors whose href matches the documented
     * URL model (/movie/, /series/, /episode/). No class names are
     * assumed — title from the link text (fallback: img alt, then
     * the slug), poster from the img inside the link. */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
            if (href.isBlank()) continue
            val kind = when {
                href.contains("/movie/") -> "movie"
                href.contains("/episode/") -> "episode"
                href.contains("/series/") -> "series"
                else -> continue
            }
            val url = resolveUrl(href)
            if (!seen.add(url)) continue
            val img = a.selectFirst("img")
            val rawTitle = a.text().trim()
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            val title = cleanTitle(
                rawTitle.ifBlank {
                    url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
                }
            )
            if (title.isBlank()) continue
            val poster = img?.let { posterUrl(it) }
            val card: SearchResponse? = when (kind) {
                "movie" -> newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
                else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            card?.let { out += it }
        }
        return out
    }

    private fun posterUrl(img: org.jsoup.nodes.Element): String? {
        val raw = img.attr("data-src").ifBlank { img.attr("src") }
            .ifBlank { img.attr("data-lazy-src") }.trim()
        if (raw.isBlank() || raw.startsWith("data:") ||
            raw.contains("blank") || raw.contains("placeholder")
        ) return null
        return resolveUrl(raw)
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = getDoc("$mainUrl/search/?q=$q") ?: return emptyList()
        return parseCards(doc).take(20)
    }

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------
    private fun detailMeta(doc: Document, url: String): Triple<String, String?, String?> {
        val rawTitle = og(doc, "title")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val title = cleanTitle(rawTitle)
        val poster = og(doc, "image")?.let { resolveUrl(it) }
        val plot = og(doc, "description")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?.trim()?.ifBlank { null }
        return Triple(title.ifBlank { "فايف تي في" }, poster, plot)
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = url.trim().trimEnd('/')
        return when {
            clean.contains("/movie/") -> loadMovie(clean)
            clean.contains("/episode/") -> loadEpisodeHub(clean)
            clean.contains("/series/") -> loadSeries(clean)
            else -> movieFallback(clean)
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val doc = getDoc(url, referer = "$mainUrl/")
        if (doc == null) return movieFallback(url)
        val (title, poster, plot) = detailMeta(doc, url)
        val text = doc.text()
        val extras = ArrayList<String>()
        Regex("""(\d+)\s*h\s*(\d+)\s*m""").find(text)?.let {
            extras += "المدة: ${it.value}"
        }
        listOf("1080p", "720p", "480p").firstOrNull { text.contains(it) }?.let {
            extras += "الجودة: $it"
        }
        val genres = doc.select("a[href*=genre]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 20 }
            .distinct().take(6)
        if (genres.isNotEmpty()) extras += "الأنواع: " + genres.joinToString("، ")
        if (text.contains("مترجم")) extras += "مترجم"
        val fullPlot = (extras + listOfNotNull(plot)).joinToString("\n")
            .ifBlank { null }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = fullPlot
        }
    }

    private suspend fun loadSeries(url: String): LoadResponse {
        val doc = getDoc(url, referer = "$mainUrl/")
        if (doc == null) return movieFallback(url)
        val (title, poster, plot) = detailMeta(doc, url)
        val slug = url.trimEnd('/').substringAfterLast('/')
        val episodes = collectEpisodes(doc, slug, poster)
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /** An episode URL opens its series hub (the episode page lists
     * its siblings, and the series page lists them too). If the hub
     * cannot be read, fall back to a single-episode series. */
    private suspend fun loadEpisodeHub(episodeUrl: String): LoadResponse {
        val tail = episodeUrl.trimEnd('/').substringAfterLast('/')
        val epNum = Regex("""-(\d+)$""").find(tail)?.groupValues?.get(1)?.toIntOrNull()
        val seriesSlug = tail.replace(Regex("""-\d+$"""), "")
        val seriesUrl = "$mainUrl/series/$seriesSlug/"
        val sdoc = getDoc(seriesUrl, referer = "$mainUrl/")
        if (sdoc != null) {
            val (title, poster, plot) = detailMeta(sdoc, seriesUrl)
            val episodes = collectEpisodes(sdoc, seriesSlug, poster)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }
        val name = cleanTitle(tail.replace('-', ' '))
        val ep = newEpisode(episodeUrl) {
            this.name = if (epNum != null) "الحلقة $epNum" else "الحلقة"
            this.season = 1
            this.episode = epNum
            this.posterUrl = null
        }
        return newTvSeriesLoadResponse(name, episodeUrl, TvType.TvSeries, listOf(ep)) {
        }
    }

    /** Episode links on a series page: same-series only (the episode
     * slug starts with the series slug + "-<n>"). Season is always
     * 1 in v1 (the study saw numbered episodes only). */
    private fun collectEpisodes(doc: Document, seriesSlug: String, poster: String?) =
        run {
            val byNum = sortedMapOf<Int, String>()
            for (a in doc.select("a[href*=\"/episode/\"]")) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (href.isBlank() || !href.contains("/episode/")) continue
                val epSlug = href.trimEnd('/').substringAfter("/episode/")
                if (seriesSlug.isNotBlank() &&
                    epSlug != seriesSlug && !epSlug.startsWith("$seriesSlug-")
                ) continue
                val num = Regex("""-(\d+)$""")
                    .find(epSlug)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                byNum.putIfAbsent(num, resolveUrl(href))
            }
            byNum.map { (n, u) ->
                newEpisode(u) {
                    this.name = "الحلقة $n"
                    this.season = 1
                    this.episode = n
                    this.posterUrl = poster
                }
            }
        }

    private suspend fun movieFallback(url: String): LoadResponse {
        val name = cleanTitle(
            url.substringAfterLast('/').replace('-', ' ')
        )
        return newMovieLoadResponse(name.ifBlank { "فيلم" }, url, TvType.Movie, url)
    }

    // ---------------------------------------------------------------
    // Playback — shared machinery
    // ---------------------------------------------------------------
    private fun extractDirect(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(clean)?.let {
            return it.value
        }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(clean)?.let {
            return it.value
        }
        return null
    }

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
            out += Regex("""\b\w+\b""").replace(payload) { mr ->
                map[mr.value] ?: mr.value
            }
        }
        return out
    }

    private suspend fun scanEmbed(
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val h = browserHeaders + ("Referer" to asciiSafe(data))
        val text = runCatching {
            app.get(embed, headers = h, interceptor = cloudflareKiller).text
        }.getOrNull() ?: return false
        val direct = extractDirect(text)
            ?: unpackPackedJs(text).firstNotNullOfOrNull { extractDirect(it) }
            ?: return false
        if (direct.isBlank()) return false
        emitDirect(direct, embed, callback)
        return true
    }

    private suspend fun resolveViaRelay(
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val relayUrl = "$relayBase/relay/resolve?url=" +
            java.net.URLEncoder.encode(embed, "UTF-8") +
            "&ref=" + java.net.URLEncoder.encode(data, "UTF-8")
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(relayUrl).text }.getOrNull()
            if (text != null) break
            Thread.sleep(2000)
        }
        val body = text ?: return 0
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return 0
        if (!json.optBoolean("ok")) return 0
        val arr = json.optJSONArray("direct") ?: return 0
        var emitted = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val link = item.optString("url")
            if (link.isNotBlank()) {
                emitDirect(link, embed, callback)
                emitted++
            }
        }
        return emitted
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            callback(
                newExtractorLink(
                    name, name, url,
                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitDebug(
        diag: List<String>,
        callback: (ExtractorLink) -> Unit
    ) {
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

    private val priorityHosts = listOf(
        "hgcloud", "vidmoly", "streamwish", "doodstream", "streamtape",
        "uqload", "voe", "filemoon", "vidhide", "mixdrop", "byse",
        "71stream", "ult4vid"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        val doc = getDoc(data, referer = "$mainUrl/")
        if (doc == null) {
            emitDebug(listOf("FiveTV v1", "page-fail", "gate?"), callback)
            return true
        }
        val pageText = doc.text()
        val vip = pageText.contains("لأعضاء VIP فقط") || pageText.contains("VIP")
        val embeds = extractEmbeds(doc, doc.html())
        val hosts = embeds.map { hostOf(it) }.joinToString(",").take(80)
        val extra = (if (vip) listOf("vip") else emptyList()) +
            listOf("hosts=$hosts")
        return runChain(
            embeds, data, subtitleCallback, callback, ::elapsed,
            allowDebug = true, diagExtra = extra
        )
    }

    /** The player lives on the detail page (#watch) with 4 server
     * buttons that swap one iframe. The static HTML was never
     * captured, so v1 collects embeds three ways: the player
     * iframe(s), data-* attributes on server buttons, and a raw
     * HTML scan for the four documented hosts + generic embed
     * URLs. The DEBUG line (hosts=) shows what was really found. */
    private fun extractEmbeds(doc: Document, html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (f in doc.select("iframe[src]")) {
            val src = f.attr("abs:src").ifBlank { f.attr("src") }.trim()
            if (src.startsWith("http") && !src.contains("5tv.lol")) {
                out += resolveUrl(src)
            }
        }
        for (el in doc.select(
            "[data-src], [data-url], [data-embed], [data-iframe], " +
                "[data-video], [data-link]"
        )) {
            for (attr in listOf(
                "data-src", "data-url", "data-embed",
                "data-iframe", "data-video", "data-link"
            )) {
                val v = el.attr(attr).trim()
                if (v.startsWith("http") && !v.contains("5tv.lol")) {
                    out += resolveUrl(v)
                }
            }
        }
        val clean = html.replace("\\/", "/")
        for (host in listOf("71stream.one", "ult4vid.one", "hgcloud", "vidmoly")) {
            Regex(
                """https?://[^\s"'<>]*""" + Regex.escape(host) + """[^\s"'<>]*"""
            ).findAll(clean).forEach {
                val u = it.value.trimEnd('\\')
                if (!u.contains("5tv.lol")) out += u
            }
        }
        Regex("""https?://[^\s"'<>]+/(e|embed|v)/[A-Za-z0-9_-]+[^\s"'<>]*""")
            .findAll(clean).forEach {
                val u = it.value.trimEnd('\\')
                if (!u.contains("5tv.lol") && !u.contains("google") &&
                    !u.contains("facebook")
                ) out += u
            }
        return out.toList()
    }

    /** Shared extractor -> dig -> relay core with caps and a budget.
     * The resolver caps are measured from THIS chain's own clock,
     * not from loadLinks entry (a slow warm-up / gate solve must
     * not starve the extractors). */
    private suspend fun runChain(
        embeds: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long,
        allowDebug: Boolean = true,
        diagExtra: List<String> = emptyList()
    ): Boolean {
        val tChain = System.currentTimeMillis()
        val chainMs = { System.currentTimeMillis() - tChain }
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        var extOk = 0
        val unresolved = mutableListOf<String>()
        val ordered = embeds.sortedByDescending { e ->
            priorityHosts.any { e.contains(it) }
        }
        for (embed in ordered) {
            if (emitted >= 5 || chainMs() > 11000) break
            val before = emitted
            runCatching { loadExtractor(embed, referer, subtitleCallback, counting) }
            if (emitted > before) extOk++ else unresolved += embed
        }
        var digOk = 0
        if (emitted < 3) {
            for (embed in unresolved) {
                if (chainMs() > 16000 || digOk >= 3) break
                if (scanEmbed(embed, referer, counting)) digOk++
            }
        }
        var relayOk = 0
        if (emitted == 0) {
            for (embed in unresolved.take(3)) {
                if (chainMs() > 22000) break
                relayOk += resolveViaRelay(embed, referer, counting)
            }
        }
        if (emitted == 0 && allowDebug) {
            emitDebug(
                listOf("FiveTV v1") + diagExtra +
                    listOf("srv=${embeds.size}", "ext=$extOk",
                        "dig=$digOk", "relay=$relayOk", "t=${elapsed() / 1000}s"),
                callback
            )
        }
        return true
    }
}
