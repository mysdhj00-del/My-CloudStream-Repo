package com.wecima2

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Wecima2 provider for CloudStream 3 — dedicated to https://wecima.cx.
 *
 * Traced live 2026-11-03/04 against the real WecimaEngine frontend
 * (a THIRD WeCima frontend: neither the WP theme on wecima.vin nor
 * the mobile theme on weciema.net):
 * - Content model: /watch/<slug>-movie (films), /series/<slug>-series
 *   (series hubs), /watch/<slug>-sNN-eMM (episodes). Series pages list
 *   their episodes as /watch/ links inside .Seasons--Episodes
 *   (verified: Between Steps 7 eps, talae-nazil 8 eps, live).
 * - Cards are div.Wecima-Card blocks: a.Wecima-Card__link carries href
 *   + title, the poster hides in a span's data-lazy-style CSS variable
 *   (--image:url(...)), title in strong.Wecima-Card__title.
 * - Categories paginate as /category/<slug>/page/N (verified: 46 pages
 *   on foreign-series). Home sections are the 12 core categories.
 * - Watch pages carry their servers as <btn data-xpage data-url>
 *   elements. data-url is NOT a plain URL: strip every '+' char, drop
 *   the first 3 chars, standard-base64-decode, prepend "https:".
 *   Verified on 13 buttons across 2 pages (film + episode):
 *   doodstream / mixdrop / luluvdo / dhcplay / savefiles / byselapuix /
 *   vidspeed / 1vid / bysesukior / hgcloud / playmogo embeds — all
 *   standard file-host embeds handed to loadExtractor.
 * - Search: POST https://wecima.cx/search with form field q=<query>
 *   (CRACKED 2026-11-04: the field NAME is the key — GET /search?s=
 *   and POST with s=/search=/keyword= all return status:false + empty,
 *   even from inside a real browser session; POST q= returns
 *   status:true + results[]. Item shape: {title, year, slug, image,
 *   istv, ribbon, rating, quality, genre}; istv 0 = film
 *   (slug ends -movie -> /watch/<slug>), 1 = series (slug ends
 *   -series -> /series/<slug>), 2 = episode (slug -sNN-eMM ->
 *   /watch/<slug>, and load() resolves it to its series).
 *   Verified live: "between steps" -> 8 results (series + E1-E7),
 *   "avengers" -> 7 film results.
 * - Catalog note: cx is a DIFFERENT catalog from vin/mobile (verified
 *   via its 52-file sitemap): no Sido, no subtitle طالع نازل — but it
 *   uniquely covers turkish-series, korean-series, tv-shows and
 *   wwe-shows, and its episode lists are complete per series.
 * - DEBUG markers in v1 (standing rule): loadLinks always emits one
 *   DEBUG source over the Mux test stream carrying page/btn/dec/ok
 *   counts, so an empty on-phone list means loadLinks never ran.
 *   v3 extends this to home: a section that parses 0 cards emits a
 *   DEBUG card carrying page length, card-marker, challenge-marker,
 *   status and page title, so an empty section on the phone is
 *   diagnosable from a screenshot instead of silent.
 * - v4 (panel-driven): when a section still parses 0 cards, fall back
 *   to the site's own RSS feed (/feed/, 40 latest items with posters),
 *   then to the working search API with a broad Latin query
 *   ("2026" for series sections, "love" for movie sections — Arabic
 *   generics return 0, verified live). Fallback sections are marked
 *   "(بديل)" in their name. Category GETs also carry Referer + Accept
 *   headers now (panel suggestion: compare against the working POST).
 * - v5 (on-device v4 verdict: home STILL shows DEBUG cards after a
 *   full restart, so feed + search fallbacks also failed from the
 *   home context while search from the search box works): warm-up —
 *   one homepage visit per client per process before sections, so
 *   any session cookie is present (panel: shared session) — plus a
 *   POST-category retry, because POST to a category URL returns the
 *   same full page as GET from the sandbox, and the edge already
 *   lets the search POST through on the phone while GETs fail.
 * - v6 (on-device v5 verdict: STILL the same failure after a full
 *   close; AI CodeOps path dead — OAuth not_configured, manual
 *   token rejected — do not pursue unless he reopens it): home
 *   sections come ONLY from the working search POST (q=2026 for
 *   series sections, q=love for movie sections). Category GETs AND
 *   the POST-category retry are GONE: on the phone they all failed,
 *   and firing them first may poison the session / trip the edge
 *   before the search even runs. The search UI path (same client,
 *   same POST, same headers) is the only proven path on his phone,
 *   so home now issues exactly that request, first and alone.
 *   runSearch also gunzips manually if the body still arrives
 *   gzip-compressed (panel gzip check). DEBUG cards are now
 *   version-tagged ("DEBUG v6 home ...") so any screenshot proves
 *   which code is actually installed.
 * - No coroutine imports anywhere (CI classpath law): plain code,
 *   Thread-free, suspend functions only where the API requires them.
 * - v7 (on-device v6 verdict 2026-11-04 20:19: the visible cards
 *   are NOT real titles — "مكتوب عليها", i.e. DEBUG cards again;
 *   search-from-home AND feed-from-home still yield 0 while the
 *   search box works; plus the user's redirect to study the Arab
 *   repo's extensions): Arab repo = arb343, Abodabodd/re-3arabi,
 *   whose WeCima (wecima.ac, sibling WecimaEngine frontend) was
 *   read in full. Its lessons, adapted here: (a) EVERY card carries
 *   posterHeaders with the Cloudflare clearance cookie — phone
 *   image loads run on a separate stack (Coil) and hit CF without
 *   it, so posters get posterHeaders here too; (b) requests run
 *   through CloudflareKiller (the app's own CF solver: passthrough
 *   unless CF answers 403/503, then WebView-solves and retries
 *   with the harvested cf_clearance) — the search POST now goes
 *   through app.post + CloudflareKiller FIRST, with the raw client
 *   only as a fallback, so a phone-side CF challenge no longer
 *   silently empties home; (c) sequentialMainPage = true (no
 *   12-section burst tripping the edge); (d) type filtering
 *   (build-off Luna idea): series sections keep istv 1 only,
 *   movie sections keep everything except istv 1; (e) DEBUG cards
 *   now carry the failure reason + response metadata (attempt,
 *   HTTP code, body length), so the next screenshot is decisive.
 *   NOT copied from the Arab provider: its hardcoded cf_clearance
 *   cookie (expires, leaks a session — CloudflareKiller solves it
 *   properly instead) and its decode variant (prepends "aHR0c";
 *   cx's drop(3) decode is verified live and stays).
 * - v9 (user 2026-11-04 23:11-23:13: "أصبح جيد لكن اريد
 *   جميع الأقسام تظهر وتعرض كل محتوى الأقسام بتمرير الجانبي
 *   بالكامل" + "مثل قسم مسلسلات رمضان 2025" + "في الإضافة
 *   cloudstream وهكذا كل الأقسام"): (a) mainPage is now the
 *   site's FULL nav, 22 sections in nav order (verified live
 *   from the homepage: 7 film, 6 series, Ramadan 2026 + 2021..
 *   2025, anime-series, tv-shows, wwe-shows) — the 10 missing
 *   ones (asian-movies, indian/asian-series, Ramadan 2021-2025,
 *   tv/wwe-shows) are added; (b) each section row now carries
 *   its FULL catalog, not just page 1: pages are fetched until
 *   a page adds zero NEW cards (the site clamps past-the-end
 *   pages to a repeated tail — verified on Ramadan 2025: 28 +
 *   28 + 24 + 5, then 5 repeated) or a 5-page cap (140 cards),
 *   with cross-page dedupe; hasNext stays true only when the
 *   cap was hit with new cards still coming, so the section
 *   detail view continues pagination from there; (c) the
 *   search-fallback section-type rule now reads the URL slug
 *   (contains "-series") instead of the label, so Ramadan-year
 *   sections fall back to series queries, not movies.
 *   Sections still load sequentially (safe with the shared
 *   CloudflareKiller cookie store); rows render progressively
 *   as each section completes.
 */
class WeCima2Provider : MainAPI() {
    override var mainUrl = "https://wecima.cx"
    override var name = "Wecima2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // v7 (Arab-repo lesson): sections load one after another, never
    // as a 12-way burst that could trip the edge before any reply.
    override var sequentialMainPage = true

    // v7: the app's own Cloudflare solver (passthrough unless CF
    // answers 403/503, then WebView-solves and retries with the
    // harvested cf_clearance, cached per host in savedCookies).
    private val cloudflareKiller by lazy { CloudflareKiller() }

    /**
     * v7 (Arab-repo lesson): poster loads run on a separate stack
     * (Coil) from API requests, so cards must carry the headers
     * themselves — browser UA + Referer, plus the cf_clearance
     * cookie once CloudflareKiller has solved one.
     */
    private fun posterHeaders(): Map<String, String> {
        val out = HashMap<String, String>()
        out["User-Agent"] = headers["User-Agent"] ?: "Mozilla/5.0"
        out["Referer"] = "$mainUrl/"
        cloudflareKiller.savedCookies["wecima.cx"]?.let { cookies ->
            if (cookies.isNotEmpty()) {
                out["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }
        return out
    }

    // v9: the site's FULL nav, 22 sections in nav order (verified
    // live from the homepage 2026-11-04) — every section the site
    // lists, not just the 12 core ones.
    override val mainPage = mainPageOf(
        "$mainUrl/category/foreign-movies" to "أفلام أجنبي",
        "$mainUrl/category/arabic-movies" to "أفلام عربي",
        "$mainUrl/category/indian-movies" to "أفلام هندي",
        "$mainUrl/category/asian-movies" to "أفلام آسيوية",
        "$mainUrl/category/turkish-movies" to "أفلام تركي",
        "$mainUrl/category/anime-movies" to "أفلام أنمي",
        "$mainUrl/category/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/category/foreign-series" to "مسلسلات أجنبي",
        "$mainUrl/category/arabic-series" to "مسلسلات عربية",
        "$mainUrl/category/indian-series" to "مسلسلات هندية",
        "$mainUrl/category/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/category/turkish-series" to "مسلسلات تركية",
        "$mainUrl/category/korean-series" to "مسلسلات كورية",
        "$mainUrl/category/ramadan-series-2026" to "مسلسلات رمضان 2026",
        "$mainUrl/category/ramadan-series-2021" to "رمضان 2021",
        "$mainUrl/category/ramadan-series-2022" to "رمضان 2022",
        "$mainUrl/category/ramadan-series-2023" to "رمضان 2023",
        "$mainUrl/category/ramadan-series-2024" to "رمضان 2024",
        "$mainUrl/category/ramadan-series-2025" to "رمضان 2025",
        "$mainUrl/category/anime-series" to "مسلسلات أنمي",
        "$mainUrl/category/tv-shows" to "برامج تلفزيونية",
        "$mainUrl/category/wwe-shows" to "عروض مصارعة",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "ar,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "https://wecima.cx/",
    )

    // ---------- cards ----------

    private fun cardPoster(anchor: Element, card: Element?): String? {
        // Homepage markup nests the poster span INSIDE the anchor;
        // category markup carries it as schema meta on the card div.
        val ownStyle = anchor.selectFirst("span[data-lazy-style]")?.attr("data-lazy-style") ?: ""
        val fromOwn = Regex("""--image:\s*url\(([^)]+)\)""").find(ownStyle)?.groupValues?.get(1)
        if (!fromOwn.isNullOrEmpty()) return fromOwn
        val ownSrc = anchor.selectFirst("span[data-src]")?.attr("data-src")?.ifEmpty { null }
        if (ownSrc != null) return ownSrc
        val ownImg = anchor.selectFirst("img[data-lazy-src], img[src]")?.let {
            val v = it.attr("data-lazy-src").ifEmpty { it.attr("src") }
            v.ifEmpty { null }
        }
        if (ownImg != null) return ownImg
        if (card != null) {
            val cardSrc = card.selectFirst("span[data-src]")?.attr("data-src")?.ifEmpty { null }
            if (cardSrc != null) return cardSrc
            val style = card.selectFirst("span[data-lazy-style]")?.attr("data-lazy-style") ?: ""
            val fromStyle = Regex("""--image:\s*url\(([^)]+)\)""").find(style)?.groupValues?.get(1)
            if (!fromStyle.isNullOrEmpty()) return fromStyle
            return card.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.ifEmpty { null }
        }
        return null
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        doc.select("a.Wecima-Card__link[href]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (href.isEmpty() || !seen.add(href)) return@forEach
            val card = a.parents().firstOrNull { it.hasClass("Wecima-Card") }
            // Title element differs by page: <strong> on the homepage,
            // <h2> on category pages; the anchor title attr covers both.
            val title = card?.selectFirst("strong.Wecima-Card__title, h2.Wecima-Card__title")?.text()?.trim()
                ?.ifEmpty { null }
                ?: a.selectFirst("strong.Wecima-Card__title, h2.Wecima-Card__title")?.text()?.trim()?.ifEmpty { null }
                ?: a.attr("title").trim().ifEmpty { null }
                ?: return@forEach
            val poster = cardPoster(a, card)?.let { fixUrl(it) }
            if (href.contains("/series/")) {
                out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders()
                })
            } else {
                out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders()
                })
            }
        }
        return out
    }

    // ---------- home ----------

    // Warm-up flags: one homepage visit per client per process, so
    // any session cookie the site sets is present before sections
    // are fetched (panel: shared session / warm-up). Duplicates
    // from concurrent section loads are harmless idempotent GETs.
    private var warmedApp = false
    private var warmedSearch = false

    private suspend fun warmUp() {
        if (!warmedApp) {
            warmedApp = true
            try { app.get(mainUrl, headers = headers, interceptor = cloudflareKiller) } catch (e: Exception) { }
        }
        if (!warmedSearch) {
            warmedSearch = true
            try {
                val req = okhttp3.Request.Builder()
                    .url(mainUrl)
                    .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                    .header("Referer", "$mainUrl/")
                    .build()
                searchClient.newCall(req).execute().close()
            } catch (e: Exception) { }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // v9: the category page itself is the home source — and
        // the row now carries the section's FULL catalog, not
        // just page 1: pages are fetched until one adds zero NEW
        // cards (the site clamps past-the-end pages to a repeated
        // tail) or a 5-page cap (140 cards). Search stays only
        // as fallback, then feed, then DEBUG.
        try { warmUp() } catch (e: Exception) { }
        var items: List<SearchResponse> = emptyList()
        var sectionName = request.name
        var catCount = -1
        var pagesFetched = 0
        var moreMayExist = false
        var fromCategory = false
        var outcome: SearchOutcome? = null
        var feedCount = 0
        run {
            val allCards = ArrayList<SearchResponse>()
            val seenUrls = HashSet<String>()
            var p = page
            while (pagesFetched < 5) {
                val pageUrl = if (p == 1) request.data else "${request.data}/page/$p"
                val parsed = try {
                    app.get(pageUrl, headers = headers, interceptor = cloudflareKiller).document
                        .let { parseCards(it) }
                } catch (e: Exception) {
                    emptyList()
                }
                pagesFetched++
                if (parsed.isEmpty()) break
                var added = 0
                for (c in parsed) {
                    if (seenUrls.add(c.url)) {
                        allCards.add(c)
                        added++
                    }
                }
                if (added == 0) break
                if (pagesFetched >= 5) {
                    moreMayExist = true
                    break
                }
                p++
            }
            catCount = allCards.size
            if (allCards.isNotEmpty()) {
                items = allCards
                fromCategory = true
            }
        }
        // v9: section type from the URL slug, not the label, so
        // Ramadan-year sections fall back to series queries.
        val isSeries = request.data.contains("-series")
        val q = if (isSeries) "2026" else "love"
        val mode = if (isSeries) "series" else "movies"
        if (items.isEmpty()) {
            outcome = try { runSearchDetailed(q, mode) } catch (e: Exception) {
                SearchOutcome(emptyList(), "none", 0, 0, "network")
            }
            items = outcome.items
        }
        if (items.isEmpty()) {
            // Second source only: the site's own RSS feed.
            val feedItems = try { fetchFeedItems() } catch (e: Exception) { emptyList() }
            feedCount = feedItems.size
            if (feedItems.isNotEmpty()) {
                items = feedItems
                sectionName = request.name + " (بديل)"
            }
        }
        if (items.isEmpty()) {
            val o = outcome
            val debugTitle = "DEBUG v9 home ${request.name} cat=$catCount pages=$pagesFetched q=$q a=${o?.attempt ?: "-"} code=${o?.code ?: 0} len=${o?.bodyLen ?: 0} r=${o?.reason ?: "nocat"} fb=$feedCount"
            items = listOf(newMovieSearchResponse(debugTitle, request.data, TvType.Movie) {})
        }
        val hasNext = fromCategory && moreMayExist
        return newHomePageResponse(sectionName, items, hasNext = hasNext)
    }

    // ---------- search ----------

    private val searchClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun searchItem(obj: JSONObject): SearchResponse? {
        val slug = obj.optString("slug")
        val title = obj.optString("title")
        if (slug.isEmpty() || title.isEmpty()) return null
        val poster = obj.optString("image").ifEmpty { null }
        val ph = posterHeaders()
        return when (obj.optInt("istv", 0)) {
            1 -> newTvSeriesSearchResponse(title, "$mainUrl/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.posterHeaders = ph
            }
            2 -> newTvSeriesSearchResponse(title, "$mainUrl/watch/$slug", TvType.TvSeries) {
                // Episode hit: load() resolves it to its full series.
                this.posterUrl = poster
                this.posterHeaders = ph
            }
            else -> newMovieSearchResponse(title, "$mainUrl/watch/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = ph
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runSearchDetailed(query, "all").items
    }

    /** Search outcome with diagnostics for the v7 DEBUG card. */
    private class SearchOutcome(
        val items: List<SearchResponse>,
        val attempt: String,
        val code: Int,
        val bodyLen: Int,
        val reason: String,
    )

    /** Parses one search-API body into cards (mode: all/series/movies). */
    private fun parseSearchBody(text: String, mode: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        try {
            val arr = JSONObject(text).optJSONArray("results") ?: return out
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val istv = obj.optInt("istv", 0)
                // v7 type filtering (build-off Luna idea): series
                // sections keep series only; movie sections drop
                // series hits; the search box ("all") keeps all.
                if (mode == "series" && istv != 1) continue
                if (mode == "movies" && istv == 1) continue
                val item = searchItem(obj) ?: continue
                if (seen.add(item.url)) out.add(item)
            }
        } catch (e: Exception) {
            // Unknown shape: never crash search, just return what we have.
        }
        return out
    }

    /**
     * POSTs the site's search API (field q=). Attempt A goes through
     * app.post + CloudflareKiller (Arab-repo lesson); attempt B is
     * the raw client, used only when A throws or answers non-2xx.
     * A 2xx empty body is accepted as genuinely empty — no second
     * attempt, so the DEBUG card reports the true first cause.
     */
    private suspend fun runSearchDetailed(query: String, mode: String): SearchOutcome {
        // Attempt A: app.post + CloudflareKiller.
        try {
            val resp = app.post(
                "$mainUrl/search",
                data = mapOf("q" to query),
                headers = mapOf(
                    "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                    "Referer" to "$mainUrl/",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                interceptor = cloudflareKiller,
            )
            val text = resp.text
            val code = resp.code
            if (code in 200..299) {
                val items = parseSearchBody(text, mode)
                val reason = if (items.isEmpty()) "empty" else "ok"
                return SearchOutcome(items, "ck", code, text.length, reason)
            }
            // Non-2xx: fall through to the raw client below.
            val ckOutcome = SearchOutcome(emptyList(), "ck", code, text.length, "http")
            return runSearchRaw(query, mode, ckOutcome)
        } catch (e: Exception) {
            return runSearchRaw(query, mode, SearchOutcome(emptyList(), "ck", 0, 0, "network"))
        }
    }

    /** Attempt B: the raw OkHttp client (pre-v7 path). */
    private fun runSearchRaw(query: String, mode: String, prior: SearchOutcome): SearchOutcome {
        val raw: ByteArray = try {
            val form = okhttp3.FormBody.Builder().add("q", query).build()
            val req = okhttp3.Request.Builder()
                .url("$mainUrl/search")
                .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                .header("Referer", "$mainUrl/")
                .post(form)
                .build()
            searchClient.newCall(req).execute().use { it.body?.bytes() } ?: return prior
        } catch (e: Exception) {
            return SearchOutcome(emptyList(), "raw", 0, 0, "network")
        }
        val text = gunzipIfNeeded(raw)
        val items = parseSearchBody(text, mode)
        val reason = if (items.isEmpty()) "empty" else "ok"
        return SearchOutcome(items, "raw", 200, text.length, reason)
    }

    /**
     * If the body still arrives gzip-compressed (panel gzip check),
     * decompress it; otherwise decode as UTF-8. OkHttp normally
     * decompresses transparently, but if any layer sets
     * Accept-Encoding manually that breaks — this is the safety net.
     */
    private fun gunzipIfNeeded(bytes: ByteArray): String {
        return try {
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * The site's own RSS feed (/feed/): 40 latest items with title,
     * link and poster. Verified live 2026-11-04. Used only as a
     * fallback when a category page yields no cards on the phone.
     */
    private suspend fun fetchFeedItems(): List<SearchResponse> {
        val text = try {
            app.get("$mainUrl/feed/", headers = headers).text
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        val itemRegex = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        for (block in itemRegex.findAll(text)) {
            val body = block.groupValues[1]
            val title = Regex("""<title>([^<]*)</title>""").find(body)?.groupValues?.get(1)?.trim()
                ?.ifEmpty { null } ?: continue
            val link = Regex("""<link>([^<]*)</link>""").find(body)?.groupValues?.get(1)?.trim()
                ?.ifEmpty { null } ?: continue
            if (!seen.add(link)) continue
            val poster = Regex("""<media:content[^>]+url="([^"]+)"""").find(body)?.groupValues?.get(1)
            if (link.contains("/series/")) {
                out.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders()
                })
            } else {
                out.add(newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeaders()
                })
            }
        }
        return out
    }

    // ---------- load ----------

    private fun og(doc: Document, property: String): String? {
        return doc.selectFirst("meta[property=$property]")?.attr("content")?.ifEmpty { null }
    }

    private fun cleanOgTitle(raw: String?): String {
        return (raw ?: "").substringBefore(" |").trim()
    }

    private fun isAnimePage(doc: Document): Boolean {
        // Breadcrumb <ol> carries the current category; the nav menus
        // are <ul>, so an <ol> hit for انمي is page-specific, not nav.
        return doc.select("ol a").any { it.text().contains("انمي") }
    }

    private val episodeUrlRegex = Regex("""-s(\d+)-e(\d+)$""")

    override suspend fun load(url: String): LoadResponse {
        // v8: detail pages go through CloudflareKiller too — v7
        // proved the phone's plain GETs get challenged (slug title
        // + 0 buttons on a real card tap).
        val doc = app.get(url, headers = headers, interceptor = cloudflareKiller).document
        val title = cleanOgTitle(og(doc, "og:title")).ifEmpty { url.substringAfterLast('/').trim() }
        val poster = og(doc, "og:image")
        val plot = og(doc, "og:description")
        val anime = isAnimePage(doc)

        if (url.contains("/series/")) {
            val episodes = doc.select(".EpisodesList a[href*=\"/watch/\"]").mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val m = episodeUrlRegex.find(href) ?: return@mapNotNull null
                val season = m.groupValues[1].toIntOrNull()
                val ep = m.groupValues[2].toIntOrNull()
                val epName = a.selectFirst("episodetitle")?.text()?.trim()?.ifEmpty { null }
                    ?: ep?.let { "الحلقة $it" }
                    ?: "حلقة"
                newEpisode(href) {
                    this.name = epName
                    this.season = season
                    this.episode = ep
                    this.posterUrl = poster
                }
            }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        if (episodeUrlRegex.containsMatchIn(url)) {
            // Episode page: resolve to its own series via slug-stem
            // match (series slug = "<stem>-YYYY-series"), so tapping
            // an episode card opens the whole series, not one episode.
            val stem = url.substringAfter("/watch/").substringBefore("-s")
            val seriesHref = doc.select("a[href*=\"/series/\"]").map { fixUrl(it.attr("href")) }
                .firstOrNull { href ->
                    val slug = href.substringAfter("/series/").removeSuffix("/")
                    val base = slug.removeSuffix("-series").replace(Regex("""-\d{4}$"""), "")
                    base == stem
                }
            if (seriesHref != null) return load(seriesHref)
            val m = episodeUrlRegex.find(url)
            val single = newEpisode(url) {
                this.name = title
                this.season = m?.groupValues?.get(1)?.toIntOrNull()
                this.episode = m?.groupValues?.get(2)?.toIntOrNull()
                this.posterUrl = poster
            }
            return newTvSeriesLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                listOf(single)
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(
            title, url,
            if (anime) TvType.Anime else TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------- playback ----------

    /** Pure-Kotlin base64 decoder (no android.util / java.util dependency). */
    private fun b64decode(input: String): String? {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = ArrayList<Int>()
        var acc = 0
        var nbits = 0
        for (ch in input) {
            if (ch == '=') break
            val v = alpha.indexOf(ch)
            if (v < 0) continue
            acc = (acc shl 6) or v
            nbits += 6
            if (nbits >= 8) {
                nbits -= 8
                bytes.add((acc shr nbits) and 0xFF)
            }
        }
        if (bytes.isEmpty()) return null
        return String(bytes.map { it.toByte() }.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Decodes a cx <btn data-url> value: strip '+' chars, drop the
     * first 3 chars, base64-decode, prepend "https:". Falls back to
     * a plain value when it already looks like a URL.
     */
    private fun decodeCxUrl(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.startsWith("http")) return v
        val cleaned = v.replace("+", "").drop(3)
        if (cleaned.length < 4) return null
        val decoded = b64decode(cleaned) ?: return null
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("http") -> decoded
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var pageCode = 0
        var btnCount = 0
        var decodedCount = 0
        var okCount = 0
        try {
            val doc = app.get(data, headers = headers, interceptor = cloudflareKiller).document
            pageCode = 200
            val btns = doc.select("btn[data-url]")
            btnCount = btns.size
            for (b in btns) {
                val embed = decodeCxUrl(b.attr("data-url")) ?: continue
                decodedCount++
                try {
                    if (loadExtractor(embed, data, subtitleCallback, callback)) okCount++
                } catch (e: Exception) {
                    // One dead host must not kill the rest.
                }
            }
        } catch (e: Exception) {
            // Fall through to the DEBUG marker below: an empty list
            // on the phone must mean loadLinks never ran, not silence.
        }
        callback(
            newExtractorLink(
                "DEBUG v1 | play | page=$pageCode | btn=$btnCount | dec=$decodedCount | ok=$okCount",
                "Mux",
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
