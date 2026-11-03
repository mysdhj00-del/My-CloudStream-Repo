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
import java.net.URLEncoder

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
 * - Search: GET /search?s=<q> returns JSON {status, results, sections}.
 *   NOTE (2026-11-04): the endpoint returns status:false + empty
 *   arrays for every query probed from outside the browser, including
 *   known-existing titles, so search() is implemented defensively
 *   against the documented shape and will light up if the site's
 *   index starts answering; a real-browser check of the site's own
 *   search box is still pending. Search never throws: empty in,
 *   empty out.
 * - Catalog note: cx is a DIFFERENT catalog from vin/mobile (verified
 *   via its 52-file sitemap): no Sido, no subtitle طالع نازل — but it
 *   uniquely covers turkish-series, korean-series, tv-shows and
 *   wwe-shows, and its episode lists are complete per series.
 * - DEBUG markers in v1 (standing rule): loadLinks always emits one
 *   DEBUG source over the Mux test stream carrying page/btn/dec/ok
 *   counts, so an empty on-phone list means loadLinks never ran.
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
    )

    // ---------- cards ----------

    private fun cardPoster(anchor: Element, card: Element?): String? {
        // Homepage markup nests the poster span INSIDE the anchor;
        // category markup carries it as schema meta on the card div.
        val ownStyle = anchor.selectFirst("span[data-lazy-style]")?.attr("data-lazy-style") ?: ""
        val fromOwn = Regex("""--image:\s*url\(([^)]+)\)""").find(ownStyle)?.groupValues?.get(1)
        if (!fromOwn.isNullOrEmpty()) return fromOwn
        val ownImg = anchor.selectFirst("img[data-lazy-src], img[src]")?.let {
            val v = it.attr("data-lazy-src").ifEmpty { it.attr("src") }
            v.ifEmpty { null }
        }
        if (ownImg != null) return ownImg
        if (card != null) {
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
            val title = card?.selectFirst("strong.Wecima-Card__title")?.text()?.trim()
                ?.ifEmpty { null }
                ?: a.selectFirst("strong.Wecima-Card__title")?.text()?.trim()?.ifEmpty { null }
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) request.data.trimEnd('/') + "/page/$page" else request.data
        val doc = try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = parseCards(doc)
        val hasNext = doc.selectFirst("a[href$=\"/page/${page + 1}\"]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ---------- search ----------

    private fun searchItem(obj: JSONObject): SearchResponse? {
        val href = obj.optString("url").ifEmpty { obj.optString("link") }
            .ifEmpty { obj.optString("href") }
        if (href.isEmpty()) return null
        val title = obj.optString("title").ifEmpty { obj.optString("name") }
        if (title.isEmpty()) return null
        val poster = obj.optString("poster").ifEmpty { obj.optString("image") }
            .ifEmpty { obj.optString("thumbnail") }
        val fixed = fixUrl(href)
        return if (fixed.contains("/series/")) {
            newTvSeriesSearchResponse(title, fixed, TvType.TvSeries) {
                this.posterUrl = poster.ifEmpty { null }
            }
        } else {
            newMovieSearchResponse(title, fixed, TvType.Movie) {
                this.posterUrl = poster.ifEmpty { null }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val text = try {
            app.get("$mainUrl/search?s=$encoded", headers = headers).text
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        try {
            val root = JSONObject(text)
            val arrays = listOf(root.optJSONArray("results"), root.optJSONArray("sections"))
            for (arr in arrays) {
                if (arr == null) continue
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val item = searchItem(obj) ?: continue
                    if (seen.add(item.url)) out.add(item)
                }
            }
        } catch (e: Exception) {
            // Unknown shape: never crash search, just return what we have.
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
