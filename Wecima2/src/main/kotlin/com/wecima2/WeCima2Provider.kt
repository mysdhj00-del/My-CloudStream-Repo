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
 * - No coroutine imports anywhere (CI classpath law): plain code,
 *   Thread-free, suspend functions only where the API requires them.
 */
class WeCima2Provider : MainAPI() {
    override var mainUrl = "https://wecima.cx"
    override var name = "Wecima2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/category/foreign-movies" to "أفلام أجنبي",
        "$mainUrl/category/arabic-movies" to "أفلام عربي",
        "$mainUrl/category/turkish-movies" to "أفلام تركي",
        "$mainUrl/category/anime-movies" to "أفلام أنمي",
        "$mainUrl/category/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/category/indian-movies" to "أفلام هندي",
        "$mainUrl/category/foreign-series" to "مسلسلات أجنبي",
        "$mainUrl/category/arabic-series" to "مسلسلات عربية",
        "$mainUrl/category/turkish-series" to "مسلسلات تركية",
        "$mainUrl/category/korean-series" to "مسلسلات كورية",
        "$mainUrl/category/anime-series" to "مسلسلات أنمي",
        "$mainUrl/category/ramadan-series-2026" to "مسلسلات رمضان 2026",
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
            val poster = cardPoster(a, card)
            if (href.contains("/series/")) {
                out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else {
                out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    // ---------- home ----------

    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en;q=0.9",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) request.data.trimEnd('/') + "/page/$page" else request.data
        var items: List<SearchResponse> = emptyList()
        var doc: Document? = null
        var pageLen = 0
        var pageTitle = ""
        var hasCardMarker = false
        var challenge = false
        var code = 0
        try {
            val res = app.get(url, headers = headers)
            val text = res.text
            pageLen = text.length
            doc = res.document
            code = 200
            hasCardMarker = text.contains("Wecima-Card__link")
            challenge = text.contains("Just a moment") ||
                text.contains("cf-challenge") ||
                text.contains("Attention Required")
            pageTitle = doc.selectFirst("title")?.text()?.take(50) ?: ""
            items = parseCards(doc)
            if (items.isEmpty()) {
                // One retry with a mobile UA: some edge rules treat
                // it differently, and it costs nothing when cards load.
                try {
                    val res2 = app.get(url, headers = mobileHeaders)
                    val doc2 = res2.document
                    val items2 = parseCards(doc2)
                    if (items2.isNotEmpty()) {
                        items = items2
                        doc = doc2
                        pageLen = res2.text.length
                        hasCardMarker = true
                        challenge = false
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            // Fall through to the DEBUG card below: an empty section
            // must never be silent about what the fetch returned.
        }
        var sectionName = request.name
        var feedCount = 0
        var searchCount = 0
        if (items.isEmpty()) {
            // Fallback 1: the site's own RSS feed (latest items).
            val feedItems = try { fetchFeedItems() } catch (e: Exception) { emptyList() }
            feedCount = feedItems.size
            if (feedItems.isNotEmpty()) {
                items = feedItems
                sectionName = request.name + " (بديل)"
            } else {
                // Fallback 2: the working search API with a broad
                // query. Latin words match; Arabic generics return 0
                // (verified live: "فيلم"/"مسلسل"/"أنمي" -> 0, while
                // "2026" -> 20 series-heavy, "love" -> 20 movie-heavy).
                val q = if (request.name.contains("مسلسلات")) "2026" else "love"
                val searchItems = try { runSearch(q) } catch (e: Exception) { emptyList() }
                searchCount = searchItems.size
                if (searchItems.isNotEmpty()) {
                    items = searchItems
                    sectionName = request.name + " (بديل)"
                }
            }
        }
        if (items.isEmpty()) {
            val debugTitle = "DEBUG home ${request.name} cards=0 len=$pageLen " +
                "card=$hasCardMarker chal=$challenge code=$code fb=$feedCount/$searchCount t=$pageTitle"
            items = listOf(newMovieSearchResponse(debugTitle, url, TvType.Movie) {})
        }
        val hasNext = if (sectionName != request.name) false
            else doc?.selectFirst("a[href$=\"/page/${page + 1}\"]") != null
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
        return when (obj.optInt("istv", 0)) {
            1 -> newTvSeriesSearchResponse(title, "$mainUrl/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
            }
            2 -> newTvSeriesSearchResponse(title, "$mainUrl/watch/$slug", TvType.TvSeries) {
                // Episode hit: load() resolves it to its full series.
                this.posterUrl = poster
            }
            else -> newMovieSearchResponse(title, "$mainUrl/watch/$slug", TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runSearch(query)
    }

    /** POSTs the site's search API (field q=) and maps its items. */
    private fun runSearch(query: String): List<SearchResponse> {
        val text = try {
            val form = okhttp3.FormBody.Builder().add("q", query).build()
            val req = okhttp3.Request.Builder()
                .url("$mainUrl/search")
                .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                .header("Referer", "$mainUrl/")
                .post(form)
                .build()
            searchClient.newCall(req).execute().use { it.body?.string() } ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        try {
            val arr = JSONObject(text).optJSONArray("results") ?: return out
            for (i in 0 until arr.length()) {
                val item = searchItem(arr.optJSONObject(i) ?: continue) ?: continue
                if (seen.add(item.url)) out.add(item)
            }
        } catch (e: Exception) {
            // Unknown shape: never crash search, just return what we have.
        }
        return out
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
                })
            } else {
                out.add(newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
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
        val doc = app.get(url, headers = headers).document
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
            val doc = app.get(data, headers = headers).document
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
