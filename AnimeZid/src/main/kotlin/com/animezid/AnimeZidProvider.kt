package com.animezid

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AnimeZid provider for CloudStream 3.
 *
 * Built against the live structure of https://animezid.org verified on 2026-10-15
 * (animezid.cam redirects there):
 * - Content cards are article.az-card > a.az-card__link with an <img> poster,
 *   a .az-card__title, and badges (.az-badge--episode holds the episode number).
 *   Series cards link to /series/{slug}/ (season cards to /series/{slug}/season/{n}/),
 *   video cards to watch.php?vid={uniq}.
 * - Category listings live at category.php?cat={slug}; videos paginate with
 *   &page={n}, series grids with &series_page={n}. Search is search.php?keywords={q}.
 * - A series page lists its seasons as cards; a season page lists its episodes
 *   as cards. A watch page's breadcrumb links back to its series.
 * - Playback is protected: watch.php links to play.php?vid={uniq}, whose page
 *   carries data-playback-create-url + data-playback-csrf (only rendered for
 *   browser-like requests). POST {create-url} with {"content_id": uniq} and the
 *   X-Playback-CSRF header creates a session listing the sources; POST
 *   {create-url}/{session}/sources/{id}/resolve returns a launch URL that
 *   redirects to (or embeds) the real host page, which the built-in extractors
 *   understand.
 *
 * v4 (2026-10-16): the relay path no longer stops at whatever the built-in
 * extractors manage to emit. On-device, only DoodStream emitted out of 29
 * relay finals while the uqload/lulustream/vidhide/streamtape embeds were
 * alive — those hosts hide the stream in packed JS or a token pattern, and
 * their stream tokens are bound to the fetching IP (verified: manifests dug
 * by the relay answer HTTP 403 from any other IP), so the dig must run on
 * the phone itself. loadViaRelay therefore digs every final the extractors
 * did not already open (see digEmbed), and the local chain digs its
 * candidates the same way.
 */
class AnimeZidProvider : MainAPI() {
    override var mainUrl = "https://animezid.org"
    override var name = "AnimeZid"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // The site only renders its protected player for browser-like requests,
    // so every call carries a desktop browser header set (verified live).
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    // Sections mirror the site's own category menu (verified live 2026-10-15).
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/category.php?cat=anime" to "أنمي",
        "$mainUrl/category.php?cat=series" to "مسلسلات",
        "$mainUrl/category.php?cat=movies" to "أفلام",
        "$mainUrl/category.php?cat=cartoon" to "كرتون",
        "$mainUrl/category.php?cat=dubbed-anime" to "أنمي مدبلج",
        "$mainUrl/category.php?cat=translated-anime" to "أنمي مترجم",
        "$mainUrl/category.php?cat=dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/category.php?cat=dubbed-animation" to "أفلام انيميشن مدبلجة",
        "$mainUrl/category.php?cat=subbed-animation" to "أفلام انيميشن مترجمة",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون",
        "$mainUrl/category.php?cat=new-eps" to "أحدث الحلقات",
        "$mainUrl/category.php?cat=new-movies" to "أحدث الأفلام",
    )

    // ---------- helpers ----------

    /** Parse one az-card anchor into a search response. */
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (!href.contains("/series/") && !href.contains("watch.php")) return null

        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("src"))?.takeUnless { it.contains("no-thumbnail") }
        val title = (
            selectFirst(".az-card__title")?.text()
                ?: img?.attr("alt")
                ?: attr("aria-label")
            ).trim()
        if (title.isBlank()) return null
        val year = Regex("""(19|20)\d{2}""").find(selectFirst(".az-card__meta")?.text().orEmpty())
            ?.value?.toIntOrNull()
        val hasEpisodeBadge = selectFirst(".az-badge--episode") != null

        return when {
            href.contains("/series/") || hasEpisodeBadge ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            else ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
        }
    }

    /** All unique content cards on a listing page (home / category / search). */
    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("article.az-card a.az-card__link").forEach { a ->
            val item = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(item.url, item)
        }
        return seen.values.toList()
    }

    private fun Document.seriesTitle(): String? {
        selectFirst("h1")?.text()?.trim()?.ifBlank { null }?.let { return it }
        // Season pages carry the series name in the hero stats ("المسلسل" label).
        select("small").firstOrNull { it.text().trim() == "المسلسل" }
            ?.parent()?.selectFirst("bdi")?.text()?.trim()?.ifBlank { null }?.let { return it }
        return selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?.substringBefore("|")?.trim()?.ifBlank { null }
    }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            ?: fixUrlNull(selectFirst(".az-cinema-poster img")?.attr("src"))
            ?: fixUrlNull(selectFirst("img[src*=\"/uploads/\"]")?.attr("src"))

    private fun Document.pagePlot(): String? =
        selectFirst(".az-series-description__copy")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst(".az-cinema-summary")?.text()?.trim()?.ifBlank { null }
            ?: select("main p").map { it.text().trim() }
                .filter { it.length in 60..2500 }
                .maxByOrNull { it.length }

    private fun Document.pageYear(): Int? =
        Regex("""(19|20)\d{2}""").find(selectFirst(".az-cinema-meta")?.text().orEmpty())
            ?.value?.toIntOrNull()

    private fun seasonNumberOf(href: String): Int? =
        Regex("""/season/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

    /** Episode cards (watch.php anchors) on a season / series page. */
    private fun Document.parseEpisodes(seasonNumber: Int?): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()
        select("article.az-card a.az-card__link[href*=\"watch.php\"], .az-cinema-episode-grid a[href*=\"watch.php\"]")
            .forEach { a ->
                val href = fixUrl(a.attr("href"))
                if (!href.contains("watch.php")) return@forEach
                val titleText = a.selectFirst(".az-card__title")?.text()?.trim()
                    ?: a.text().trim()
                val epNum = a.selectFirst(".az-badge--episode strong")?.text()?.toIntOrNull()
                    ?: Regex("""الحلقة\s*(\d+)""").find(titleText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""الحلقة\s*(\d+)""").find(a.attr("aria-label"))?.groupValues?.get(1)?.toIntOrNull()
                val ep = newEpisode(href, {
                    this.name = titleText.ifBlank { epNum?.let { n -> "الحلقة $n" } ?: "حلقة" }
                    this.season = seasonNumber
                    this.episode = epNum
                })
                byUrl.putIfAbsent(href, ep)
            }
        return byUrl.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    // ---------- MainAPI ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Category pages paginate videos with &page={n} and series grids with
        // &series_page={n}; advancing both keeps mixed grids moving together.
        val url = if (page <= 1) request.data else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page&series_page=$page"
        }
        val document = app.get(url, headers = browserHeaders).document
        val items = document.parseCards()
        return newHomePageResponse(
            request.name, items,
            hasNext = request.data.contains("category.php") && items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return app.get(url, headers = browserHeaders).document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = loadInner(url)
        maybePrewarm(url)
        return resp
    }

    private suspend fun loadInner(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document

        return when {
            url.contains("/season/") -> {
                val title = document.seriesTitle() ?: throw ErrorLoadingException("No title")
                newTvSeriesLoadResponse(
                    title, url, TvType.Anime,
                    document.parseEpisodes(seasonNumberOf(url))
                ) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                }
            }

            url.contains("/series/") -> {
                val title = document.seriesTitle() ?: throw ErrorLoadingException("No title")
                val seasonLinks = LinkedHashMap<String, Int?>()
                document.select("a[href*=\"/season/\"]").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (href.contains("/season/")) seasonLinks.putIfAbsent(href, seasonNumberOf(href))
                }
                val episodes = mutableListOf<Episode>()
                if (seasonLinks.isEmpty()) {
                    episodes += document.parseEpisodes(1)
                } else {
                    seasonLinks.entries.forEachIndexed { index, (seasonUrl, seasonNum) ->
                        val seasonDoc = runCatching {
                            app.get(seasonUrl, headers = browserHeaders).document
                        }.getOrNull() ?: return@forEachIndexed
                        episodes += seasonDoc.parseEpisodes(seasonNum ?: (index + 1))
                    }
                }
                newTvSeriesLoadResponse(
                    title, url, TvType.Anime,
                    episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
                ) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                }
            }

            url.contains("watch.php") -> {
                // An episode's watch page links back to its series in the
                // breadcrumb; jump there so the user gets the episode list.
                val seriesUrl = document.select(".az-cinema-breadcrumb a[href*=\"/series/\"]")
                    .map { fixUrl(it.attr("href")) }
                    .firstOrNull { it.contains("/series/") && !it.contains("/season/") }
                if (seriesUrl != null && seriesUrl != url) {
                    load(seriesUrl)
                } else {
                    val title = document.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                        ?: throw ErrorLoadingException("No title")
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = document.pagePoster()
                        this.plot = document.pagePlot()
                        this.year = document.pageYear()
                    }
                }
            }

            else -> throw ErrorLoadingException("Unsupported url")
        }
    }

    // ------------------------------------------------------------ v11
    // Pre-warm + streaming (panel-approved build, Mohamed 2026-10-17).
    // Measured basis: relay sessions live >= 300 s and their resolves
    // are idempotent, so the relay flow may run while the user is
    // still reading the episode page; the tap then spends the cached
    // finals and collapses to the phone-side pass. On a cold tap the
    // NDJSON stream lets the pass start on the first arrivals instead
    // of waiting out the whole relay response.

    private data class WarmEntry(val ts: Long, val finals: org.json.JSONArray)

    private val warmCache = java.util.concurrent.ConcurrentHashMap<String, WarmEntry>()
    private val warmInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Fire-and-forget pre-warm: when an EPISODE page is opened (its
     * URL carries vid=), collect the relay stream in a background
     * thread and cache the finals for the tap. Series pages have no
     * vid in the URL and are skipped by construction. */
    private fun maybePrewarm(url: String) {
        val vid = Regex("""vid=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val hit = warmCache[vid]
        if (hit != null && System.currentTimeMillis() - hit.ts < 240_000) return
        if (warmInFlight.putIfAbsent(vid, true) != null) return
        val t = Thread {
            try {
                val sink = mutableListOf<String>()
                val finals = readStreamFinals(vid, sink) { false }
                if (finals.length() > 0) {
                    warmCache[vid] = WarmEntry(System.currentTimeMillis(), finals)
                    while (warmCache.size > 8) {
                        val oldest = warmCache.entries.minByOrNull { it.value.ts }?.key ?: break
                        warmCache.remove(oldest)
                    }
                }
            } catch (e: Exception) {
                // pre-warm is best-effort; the tap paths are unaffected
            } finally {
                warmInFlight.remove(vid)
            }
        }
        t.isDaemon = true
        t.name = "az-prewarm"
        t.start()
    }

    /** Blocking NDJSON reader for /relay/animezid?stream=1. Collects
     * finals as they arrive; [gate] runs after every final and may
     * stop the read early by returning true. Overall deadline 45 s. */
    private fun readStreamFinals(
        vid: String,
        diag: MutableList<String>,
        gate: (org.json.JSONArray) -> Boolean
    ): org.json.JSONArray {
        val finals = org.json.JSONArray()
        val t0 = System.currentTimeMillis()
        var conn: java.net.HttpURLConnection? = null
        try {
            val u = java.net.URL("https://egydead-stremio.onrender.com/relay/animezid?vid=$vid&stream=1")
            conn = u.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Accept", "application/x-ndjson")
            val code = conn.responseCode
            if (code != 200) {
                diag += "str:http=$code"
                return finals
            }
            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, "UTF-8"))
            while (true) {
                if (System.currentTimeMillis() - t0 > 45_000) {
                    diag += "str:deadline"
                    break
                }
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (obj.optString("t")) {
                    "final" -> {
                        val f = JSONObject()
                        f.put("provider", obj.optString("provider", "unknown"))
                        f.put("url", obj.optString("url", ""))
                        finals.put(f)
                        if (finals.length() == 1) {
                            diag += "str:first=" + (System.currentTimeMillis() - t0) + "ms"
                        }
                        if (gate(finals)) {
                            diag += "str:gate@" + finals.length()
                            break
                        }
                    }
                    "done" -> {
                        diag += "str:done=" + (System.currentTimeMillis() - t0) + "ms"
                        return finals
                    }
                }
            }
        } catch (e: Exception) {
            diag += "str:fail"
        } finally {
            runCatching { conn?.disconnect() }
        }
        return finals
    }

    /** Live tap path over the stream: read until the first
     * Doodstream-class final lands (or the stream ends), then run the
     * standard pass over what has arrived. */
    private suspend fun loadViaStream(
        vid: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val finals = readStreamFinals(vid, diag) { arr ->
            (0 until arr.length()).any { i ->
                val u = arr.optJSONObject(i)?.optString("url").orEmpty()
                u.contains("playmogo") || u.contains("doodstream") || u.contains("dood.")
            }
        }
        if (finals.length() == 0) {
            diag += "str:empty"
            return false
        }
        diag += "str:n=${finals.length()}"
        val r = runPass(finals, referer, subtitleCallback, callback, diag, skipDood = false)
        diag += "relay:digs=${r.dug}"
        if (r.found || r.dug > 0) {
            diag += "str:hit"
            return true
        }
        diag += "str:miss"
        return false
    }

    /** Spend a fresh pre-warm entry (<= 240 s old) before any network
     * path: the tap collapses to the phone-side pass only. */
    private suspend fun loadViaWarmCache(
        vid: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val e = warmCache[vid] ?: run {
            diag += "warm:miss"
            return false
        }
        val age = (System.currentTimeMillis() - e.ts) / 1000
        if (age > 240) {
            diag += "warm:stale"
            return false
        }
        diag += "warm=hit"
        diag += "warm:age=${age}s"
        diag += "warm:finals=${e.finals.length()}"
        val r = runPass(e.finals, referer, subtitleCallback, callback, diag, skipDood = false)
        diag += "relay:digs=${r.dug}"
        if (r.found || r.dug > 0) {
            diag += "warm:win"
            return true
        }
        diag += "warm:dead"
        return false
    }

    private data class PassResult(val found: Boolean, val dug: Int)

    /** 2-attempt relay fetch + JSON parse; logs "<tag>:fetch-fail /
     * bad-json / not-ok" and returns null on any failure. */
    private suspend fun fetchRelayJson(url: String, diag: MutableList<String>, tag: String): JSONObject? {
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(url).text }.getOrNull()
            if (text != null) break
            Thread.sleep(2000)
        }
        val body = text ?: run {
            diag += "$tag:fetch-fail"
            return null
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: run {
            diag += "$tag:bad-json"
            return null
        }
        if (!json.optBoolean("ok")) {
            diag += "$tag:not-ok"
            return null
        }
        return json
    }

    /**
     * v10: FAST LANE FIRST. The relay's fast endpoint runs the same
     * protected session flow but resolves only the priority sources and
     * stops at a live Doodstream (or 3 finals), answering in ~2-3 s
     * instead of the full ~4.5-7 s, and it classifies the Doodstream
     * file server-side (ds=dead means its embed is the site's plain
     * "Video not found" page - the pass then skips it entirely). When
     * the fast answer produces a playable link we are done; otherwise
     * the full relay flow below runs exactly as v9 did.
     */
    private suspend fun loadViaRelay(
        vid: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        if (loadViaStream(vid, referer, subtitleCallback, callback, diag)) return true

        val fastUrl = "https://egydead-stremio.onrender.com/relay/animezid?vid=$vid&fast=1"
        val fast = fetchRelayJson(fastUrl, diag, "fast")
        if (fast != null) {
            diag += "fast=hit"
            val ds = fast.optString("ds", "none")
            diag += "ds=$ds"
            val stages = fast.optJSONObject("diag")?.optJSONObject("stages")
            if (stages != null) diag += "fst=" + stages.optLong("totalMs", 0) + "ms"
            val fFinals = fast.optJSONArray("finals")
            diag += "fast:finals=${fFinals?.length() ?: 0}"
            if (fFinals != null && fFinals.length() > 0) {
                val r = runPass(fFinals, referer, subtitleCallback, callback, diag, skipDood = ds == "dead")
                diag += "relay:digs=${r.dug}"
                if (r.found || r.dug > 0) {
                    diag += "relay:hit"
                    return true
                }
            }
            diag += "fast:miss"
        }
        return loadViaRelayFull(vid, referer, subtitleCallback, callback, diag)
    }

    /** The full relay flow (v9 behaviour, unchanged): every source
     * resolved server-side, MegaMax expanded, then the pass. */
    private suspend fun loadViaRelayFull(
        vid: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val relayUrl = "https://egydead-stremio.onrender.com/relay/animezid?vid=$vid"
        val json = fetchRelayJson(relayUrl, diag, "relay") ?: return false
        val finals = json.optJSONArray("finals")
        diag += "relay:finals=${finals?.length() ?: 0}"
        if (finals == null) return false
        val r = runPass(finals, referer, subtitleCallback, callback, diag, skipDood = false)
        diag += "relay:digs=${r.dug}"
        if (r.found || r.dug > 0) diag += "relay:hit"
        return r.found || r.dug > 0
    }

    /** The v9 pass over one finals array: Doodstream-first parallel
     * phase, then parallel windows over the rest, then the phone-side
     * dig stage - under the hard time budget. [skipDood] drops the
     * Doodstream finals from every phase (server-flagged dead file). */
    private suspend fun runPass(
        finals: org.json.JSONArray,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>,
        skipDood: Boolean
    ): PassResult {
        var found = false
        var dug = 0
        val extracted = HashSet<String>()
        val seenDig = HashSet<String>()
        if (finals.length() > 0) {
            // v9 (Mohamed's order 2026-10-17, after v8 still felt slow):
            // start DIRECTLY with Doodstream - on his device it is the
            // only host class that actually plays; everything else just
            // eats time. Phase 1 therefore tries ONLY the Doodstream
            // finals (the embedded playmogo page plus the MegaMax
            // doodstream.com mirrors) in one small parallel window, and
            // the moment one of them emits, the pass is DONE - playback
            // can start. Phase 2 (the v8 parallel windows over every
            // remaining class, then the phone-side dig stage) runs ONLY
            // when Doodstream produced nothing; the rest are the tail,
            // exactly as he asked. Budgets unchanged from v8: stop at 6
            // emitted, 10 s once anything playable exists, 18 s absolute.
            val passStart = System.currentTimeMillis()
            val emitted = java.util.concurrent.atomic.AtomicInteger(0)
            val parFound = java.util.concurrent.atomic.AtomicBoolean(false)
            val emitLock = Any()
            val counting: (ExtractorLink) -> Unit = { link ->
                emitted.incrementAndGet()
                synchronized(emitLock) { callback(link) }
            }
            val rawFinals = (0 until finals.length())
                .map { finals.optJSONObject(it)?.optString("url").orEmpty() }
                .filter { it.isNotBlank() && !it.contains("megamax.me") }
            val demotedHosts = listOf("mp4upload.", "ds2play.", "rpmhub.", "byseraguci.", "streamtape.")
            val orderedFinals = rawFinals.filter { u -> demotedHosts.none { u.contains(it) } } +
                rawFinals.filter { u -> demotedHosts.any { u.contains(it) } }
            val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
            val parExtracted = java.util.Collections.synchronizedSet(HashSet<String>())
            fun isDood(u: String): Boolean =
                u.contains("playmogo") || u.contains("doodstream") || u.contains("dood.")
            val dsFinals = if (skipDood) emptyList() else orderedFinals.filter { isDood(it) }
            val restFinals = orderedFinals.filter { !isDood(it) }
            suspend fun tryParallel(urls: List<String>) {
                urls.amap { url ->
                    if (loadExtractor(url, referer, subtitleCallback, counting)) {
                        parFound.set(true)
                        parExtracted += url
                    } else {
                        failed += url
                    }
                }
            }
            // Phase 1: Doodstream only, in parallel.
            if (dsFinals.isNotEmpty()) {
                tryParallel(dsFinals)
                diag += if (emitted.get() > 0) "ds=hit" else "ds:miss"
            } else {
                diag += if (skipDood) "ds:skip" else "ds:none"
            }
            // Phase 2: everything else, only if Doodstream gave nothing.
            if (emitted.get() == 0) {
                var idx = 0
                while (idx < restFinals.size) {
                    val elapsed = System.currentTimeMillis() - passStart
                    if (emitted.get() >= 6) break
                    if (emitted.get() > 0 && elapsed > 10_000) { diag += "pass:deadline"; break }
                    if (emitted.get() == 0 && elapsed > 18_000) { diag += "pass:deadline0"; break }
                    val window = restFinals.subList(idx, minOf(idx + 6, restFinals.size))
                    tryParallel(window)
                    idx += window.size
                }
                if (parFound.get()) found = true
                extracted += parExtracted
                // Dig stage: sequential by design (each dig is a full
                // page fetch + unpack), only for finals the parallel
                // pass could not open, while short on links and inside
                // budget.
                for (url in failed) {
                    if (dug >= 3 || emitted.get() >= 5) break
                    if (System.currentTimeMillis() - passStart > 12_000) break
                    val diggable = url.contains("uqload.") || url.contains("lulustream") ||
                        url.contains("streamtape") || url.contains("vidhide")
                    if (!diggable) continue
                    val key = if (url.contains("uqload.", ignoreCase = true)) {
                        "uqload/" + url.substringAfter("://").substringAfter("/", "")
                    } else url
                    if (!seenDig.add(key)) continue
                    if (key.startsWith("uqload/") &&
                        extracted.any { it.contains("uqload.", ignoreCase = true) }
                    ) continue
                    if (digEmbed(url, referer, counting, diag)) dug++
                }
            } else {
                if (parFound.get()) found = true
                extracted += parExtracted
            }
            diag += "pass:par"
            diag += "pass=" + ((System.currentTimeMillis() - passStart) / 1000) + "s"
        }
        return PassResult(found, dug)
    }

    // ------------------------------------------------------------ v4 dig
    // Hosts whose stream is never statically diggable (captcha gates,
    // pass_md5 or form two-steps, JS app shells) — the built-in extractors
    // own them where they work; digging them would only burn time.
    private val digSkipHost = Regex(
        """(megamax\.|mixdrop|mxdrop|krakenfiles|upns\.|dood|playmogo|ds2play|streamwish|rpmhub|rpmshare|byse|mp4upload|savefiles)""",
        RegexOption.IGNORE_CASE
    )
    private val directJunk = Regex(
        """(\.vtt|\.svg|\.png|\.jpe?g|\.gif|logo|thumb|/images/|sprite)""",
        RegexOption.IGNORE_CASE
    )

    private fun hostOf(url: String): String =
        Regex("""://([^/]+)""").find(url)?.groupValues?.get(1).orEmpty()

    /** First non-junk direct media URL in a page text (master playlist wins). */
    private fun extractDirectAz(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            if (!directJunk.containsMatchIn(it.groupValues[1])) return it.groupValues[1]
        }
        val m3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean)
            .map { it.value }.filter { !directJunk.containsMatchIn(it) }.toList()
        if (m3u8s.isNotEmpty()) {
            return m3u8s.firstOrNull { it.contains("master") } ?: m3u8s.first()
        }
        return Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean)
            .map { it.value }.firstOrNull { !directJunk.containsMatchIn(it) }
    }

    // Dean Edwards packer blocks — same unpacker as the Brestj/MyCima
    // providers (proven on hdup400/minochinos packed players).
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
     * Phone-side dig of one embed page: fetch it with the provider's own
     * browser headers and pull the direct stream out — a streamtape
     * get_video token URL, or a direct file in the raw or unpacked page.
     * The emitted link's referer is the embed page itself. Returns true
     * when a link was emitted.
     */
    private suspend fun digEmbed(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val host = hostOf(embed)
        if (host.isBlank() || digSkipHost.containsMatchIn(host)) return false
        val label = host.removePrefix("www.").substringBefore(".")
            .replaceFirstChar { it.uppercase() }
        val text = runCatching {
            app.get(embed, headers = browserHeaders, referer = referer).text
        }.getOrNull() ?: return false
        val direct: String?
        val directType: ExtractorLinkType
        if (host.contains("streamtape", ignoreCase = true)) {
            val m = Regex("""get_video\?id=[^"'&\s]+&expires=\d+&ip=[^"'&\s]+&token=[^"'&\s]+""")
                .find(text)
            direct = m?.let { "https://$host/${it.value}" }
            directType = ExtractorLinkType.VIDEO
        } else {
            direct = extractDirectAz(text)
                ?: unpackPackedJs(text).firstNotNullOfOrNull { extractDirectAz(it) }
            directType = if (direct?.contains(".m3u8") == true) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
        }
        if (direct.isNullOrBlank()) {
            diag += "dig:$label=0"
            return false
        }
        callback(
            newExtractorLink(label, label, direct, directType) {
                this.referer = embed
                this.quality = Qualities.Unknown.value
            }
        )
        diag += "dig:$label=1"
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Field diagnostics: stage markers are surfaced as one playable DEBUG
        // source when nothing playable is found, so the failing stage can be
        // read off the player's source list.
        val diag = mutableListOf<String>()

        val vid = Regex("""vid=([A-Za-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            diag += "vid:missing"
            emitDebug(diag, callback)
            return true
        }
        // v3: server-side relay first — the relay runs the protected
        // playback-session flow from its own network (which the site
        // answers normally) and returns final host URLs ready for the
        // built-in extractors. The relay (free tier) can be asleep, so one
        // retry is allowed inside loadViaRelay; the local chain below
        // remains the fallback.
        if (loadViaWarmCache(vid, data, subtitleCallback, callback, diag)) return true

        if (loadViaRelay(vid, data, subtitleCallback, callback, diag)) return true

        val playUrl = "$mainUrl/play.php?vid=$vid"

        val playResponse = runCatching {
            app.get(playUrl, headers = browserHeaders, referer = data)
        }.getOrNull()
        if (playResponse == null) {
            diag += "play:fetch-fail"
            emitDebug(diag, callback)
            return true
        }
        val playDoc = playResponse.document
        val mainEl = playDoc.selectFirst("[data-playback-create-url]")
            ?: playDoc.selectFirst("main")
        val createUrl = mainEl?.attr("data-playback-create-url").orEmpty()
        val csrf = mainEl?.attr("data-playback-csrf").orEmpty()
        val uniq = mainEl?.attr("data-video-uniq")?.ifBlank { null } ?: vid
        diag += "protected=${mainEl?.attr("data-playback-protected") ?: "?"}"
        if (createUrl.isBlank() || csrf.isBlank()) {
            diag += "play:no-session-attrs"
            emitDebug(diag, callback)
            return true
        }
        val cookies = runCatching { playResponse.cookies }.getOrNull().orEmpty()

        val apiHeaders = browserHeaders + mapOf(
            "X-Playback-CSRF" to csrf,
            "Referer" to playUrl,
            "Origin" to mainUrl
        )
        val jsonType = "application/json".toMediaType()

        val sessionText = runCatching {
            app.post(
                createUrl,
                headers = apiHeaders,
                cookies = cookies,
                requestBody = JSONObject().put("content_id", uniq).toString()
                    .toRequestBody(jsonType)
            ).text
        }.getOrNull()
        val session = sessionText?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sessionId = session?.optString("session_id").orEmpty()
        if (sessionId.isBlank()) {
            diag += "session:fail"
            emitDebug(diag, callback)
            return true
        }
        val sourcesJson = session?.optJSONArray("sources")
        val sourceIds = mutableListOf<Pair<String, String>>()
        if (sourcesJson != null) {
            for (i in 0 until sourcesJson.length()) {
                val s = sourcesJson.optJSONObject(i) ?: continue
                if (s.optString("type") == "embedded_web") {
                    sourceIds += s.optString("id") to s.optString("provider")
                }
            }
        }
        diag += "sources=${sourceIds.size}"
        if (sourceIds.isEmpty()) {
            emitDebug(diag, callback)
            return true
        }

        // Try the sources one at a time, best-supported hosts first, and
        // STOP at the first playable result: the site throttles resolve
        // calls hard (HTTP 429 after a small burst — verified live:
        // back-to-back resolves gave 201 then nine 429s), so resolving
        // everything, or continuing after a success, kills the rest.
        val priority = listOf(
            "PlayMate", "DoodStream", "Uqload", "Abyss", "VidTube",
            "StreamP2P", "UPNShare", "RPMShare", "StreamRuby", "MegaMax"
        )
        val ordered = sourceIds.sortedBy { pair ->
            val idx = priority.indexOf(pair.second)
            if (idx < 0) priority.size else idx
        }

        var found = false
        for ((sourceId, provider) in ordered) {
            if (found) break

            // Resolve, with one backoff retry on the site's rate limit.
            var launchUrl = ""
            var resolveCode = -1
            for (attempt in 1..2) {
                val res = runCatching {
                    app.post(
                        "$createUrl/$sessionId/sources/$sourceId/resolve",
                        headers = apiHeaders,
                        cookies = cookies,
                        requestBody = "{}".toRequestBody(jsonType)
                    )
                }.getOrNull()
                resolveCode = res?.code ?: -1
                launchUrl = res?.text
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optString("launch_url").orEmpty()
                if (launchUrl.isNotBlank()) break
                if (resolveCode == 429 && attempt == 1) {
                    Thread.sleep(3000)
                } else {
                    break
                }
            }
            if (launchUrl.isBlank()) {
                diag += "resolve:$resolveCode($provider)"
                continue
            }

            // The launch URL is bound to the playback session cookie:
            // following it without the cookie returns HTTP 410 and stays on
            // animezid.org (verified live), so the cookies MUST go along.
            // The launch either redirects straight to the host's embed page
            // (the final URL after redirects) or serves a page holding the
            // host embed in an iframe. Feed both forms to the extractors.
            val launchResponse = runCatching {
                app.get(launchUrl, headers = browserHeaders, cookies = cookies, referer = playUrl)
            }.getOrNull()
            val candidates = LinkedHashSet<String>()
            launchResponse?.url?.takeIf { it.isNotBlank() && !it.contains("animezid.org") }
                ?.let { finalUrl ->
                    candidates += finalUrl
                    // The Uqload extractor does not know the .vc TLD; the
                    // same embed path works on its canonical .com domain.
                    if (finalUrl.contains("uqload.", ignoreCase = true)) {
                        candidates += finalUrl.replace(
                            Regex("""uqload\.[a-z]+""", RegexOption.IGNORE_CASE),
                            "uqload.com"
                        )
                    }
                }
            launchResponse?.document?.select("iframe[src]")?.forEach { frame ->
                val src = fixUrl(frame.attr("src"))
                if (src.startsWith("http")) candidates += src
            }
            diag += "launch($provider)=${candidates.size}"
            if (candidates.isEmpty() && launchResponse != null) {
                diag += "launch:http-${launchResponse.code}($provider)"
            }

            for (candidate in candidates) {
                if (loadExtractor(candidate, playUrl, subtitleCallback, callback)) {
                    found = true
                    break
                }
                // The MegaMax host has no built-in extractor; its player page
                // is an Inertia app whose deferred "streams" prop lists the
                // real mirrors on well-known hosts (same trick as EgyDead).
                if (candidate.contains("megamax", ignoreCase = true) &&
                    loadFromMegaMax(candidate, playUrl, subtitleCallback, callback, diag)
                ) {
                    found = true
                    break
                }
                // v4: phone-side dig for packed/token hosts the extractors
                // miss (the local chain keeps its stop-at-first-playable
                // discipline — the site throttles resolves, not host GETs,
                // but one playable source is this fallback's contract).
                if (digEmbed(candidate, playUrl, callback, diag)) {
                    found = true
                    break
                }
            }
            if (!found) Thread.sleep(600)
        }
        if (!found) {
            emitDebug(diag, callback)
            return true
        }
        return found
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

    /**
     * Resolves a MegaMax embed page (Inertia/Vue app): the initial HTML only
     * carries video metadata plus an asset "version"; the actual sources live
     * in a deferred "streams" prop fetched with an Inertia partial reload.
     * That prop lists, per quality, mirror links on well-known hosts — all
     * understood by CloudStream's built-in extractors. Same host system as
     * EgyDead's default server; verified live on 2026-10-15.
     */
    private suspend fun loadFromMegaMax(
        embed: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val pageText = runCatching {
            app.get(embed, headers = browserHeaders, referer = referer).text
        }.getOrNull() ?: return false
        val pageJsonText = Regex(
            """<script[^>]*data-page[^>]*>(\{.*?\})</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(pageText)?.groupValues?.get(1) ?: return false
        val pageJson = runCatching { JSONObject(pageJsonText) }.getOrNull() ?: return false
        val version = pageJson.optString("version")
        if (version.isBlank()) return false
        val component = pageJson.optString("component").ifBlank { "files/mirror/video" }

        val streamsText = runCatching {
            app.get(
                embed,
                referer = embed,
                headers = browserHeaders + mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to component,
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        }.getOrNull() ?: return false

        val streams = runCatching {
            JSONObject(streamsText).getJSONObject("props").getJSONObject("streams")
        }.getOrNull() ?: return false
        if (streams.optString("status") != "success") return false
        val qualities = streams.optJSONArray("data") ?: return false

        var found = false
        for (i in 0 until qualities.length()) {
            val mirrors = qualities.optJSONObject(i)?.optJSONArray("mirrors") ?: continue
            for (j in 0 until mirrors.length()) {
                var link = mirrors.optJSONObject(j)?.optString("link").orEmpty()
                if (link.startsWith("//")) link = "https:$link"
                if (link.isBlank()) continue
                if (loadExtractor(link, embed, subtitleCallback, callback)) {
                    found = true
                } else if (digEmbed(link, embed, callback, diag)) {
                    found = true
                }
            }
        }
        diag += "megamax:mirrors-done"
        return found
    }
}
