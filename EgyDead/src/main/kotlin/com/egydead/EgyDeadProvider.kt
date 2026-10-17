package com.egydead

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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
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
 * EgyDead provider for CloudStream 3.
 *
 * Built against the live structure of https://tv10.egydead.live verified on 2026-10-15:
 * - Content cards are a bare <a> wrapping <img src> + <h1 class="BottomTitle"> +
 *   <span class="cat_name"> (in archives wrapped by li.movieItem).
 * - Posts live at /{slug}/, series at /serie/{slug}/, seasons at /season/{slug}/,
 *   episodes at /episode/{slug}/ (slug carries sXXeYY).
 * - Search is WordPress style: /?s={query}. Pagination is {base}?page={n}/.
 * - Watching is unlocked by POSTing View=1 to the same post URL (.watchNow form);
 *   the watch view exposes .serversList li[data-link] embeds and a player iframe
 *   under .watchAreaMaster .holder iframe.
 */
class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv10.egydead.live"
    override var name = "EgyDead"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // The site's CDN rejects the HTTP library's default user agent outright
    // (HTTP 520, verified live 2026-10-15: okhttp UA gets 520 on GET and POST,
    // a browser UA gets the real pages), so every request in this provider
    // carries a browser header set. The identity is an ANDROID Chrome one:
    // the app runs on phones behind mobile-carrier IPs, and a desktop
    // identity arriving from a mobile network is itself a bot-score signal.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    // Sections mirror the site's own menus (verified live 2026-10-15):
    // the /h3/ home feed, the /page/movies/ archive, the movie categories
    // (WordPress "category" taxonomy is movies-only on this site), the
    // series categories under /series-category/, and the genre list under
    // /type/ with the site's Arabic labels. Every section is a paginated
    // archive ({base}?page={n}/, 48 cards per page).
    override val mainPage = mainPageOf(
        "$mainUrl/h3/" to "المضاف حديثا",
        "$mainUrl/page/movies/" to "أفلام",
        "$mainUrl/category/english-movies/" to "أفلام أجنبية",
        "$mainUrl/category/english-movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/" to "أفلام أجنبية مدبلجة",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to "أفلام كرتون",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%88%d8%ab%d8%a7%d8%a6%d9%82%d9%8a%d8%a9/" to "أفلام وثائقية",
        "$mainUrl/category/hindi-movies/" to "أفلام هندية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "أفلام تركية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "أفلام أنمي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "أفلام عربية",
        "$mainUrl/category/%d8%b9%d8%b1%d9%88%d8%b6-%d9%88%d8%ad%d9%81%d9%84%d8%a7%d8%aa/" to "عروض وحفلات",
        "$mainUrl/category/%d8%b1%d9%8a%d8%a7%d8%b6%d8%a9/" to "رياضة",
        "$mainUrl/series-category/english-series/" to "مسلسلات أجنبية",
        "$mainUrl/series-category/arabic-series/" to "مسلسلات عربية",
        "$mainUrl/series-category/turkish-series/" to "مسلسلات تركية",
        "$mainUrl/series-category/asian-series/" to "مسلسلات آسيوية",
        "$mainUrl/series-category/anime-series/" to "مسلسلات أنمي",
        "$mainUrl/series-category/cartoon-series/" to "مسلسلات كرتون",
        "$mainUrl/series-category/indian-series/" to "مسلسلات هندية",
        "$mainUrl/series-category/english-series-dubbed/" to "مسلسلات أجنبية مدبلجة",
        "$mainUrl/series-category/tv-shows/" to "برامج تلفزيونية",
        "$mainUrl/type/action/" to "أكشن",
        "$mainUrl/type/thriller/" to "إثارة",
        "$mainUrl/type/drama/" to "دراما",
        "$mainUrl/type/comedy/" to "كوميديا",
        "$mainUrl/type/horror/" to "رعب",
        "$mainUrl/type/sci-fi/" to "خيال علمي",
        "$mainUrl/type/romance/" to "رومانسي",
        "$mainUrl/type/crime/" to "جريمة",
        "$mainUrl/type/mystery/" to "غموض",
        "$mainUrl/type/adventure/" to "مغامرة",
        "$mainUrl/type/fantasy/" to "فانتازيا",
        "$mainUrl/type/animation/" to "انيميشن",
        "$mainUrl/type/war/" to "حربي",
        "$mainUrl/type/family/" to "عائلي",
        "$mainUrl/type/history/" to "تاريخي",
        "$mainUrl/type/documentary/" to "وثائقي",
        "$mainUrl/type/super-power/" to "قوة خارقة",
        "$mainUrl/type/martial-arts/" to "فنون قتالية",
        "$mainUrl/type/supernatural/" to "خارق للطبيعة",
        "$mainUrl/type/shounen/" to "شونين",
        "$mainUrl/type/isekai/" to "ايسيكاي",
        "$mainUrl/type/school/" to "مدرسي",
        "$mainUrl/type/sports/" to "رياضي",
        "$mainUrl/type/classic-movies/" to "كلاسيك",
        "$mainUrl/type/western/" to "ويسترن",
        "$mainUrl/type/slice-of-life/" to "شريحة من الحياة",
    )

    // ---------- helpers ----------

    private fun detectType(catName: String?, href: String): TvType {
        val cat = catName.orEmpty()
        return when {
            cat.contains("انمي") || cat.contains("أنمي") || cat.contains("انميات") -> TvType.Anime
            cat.contains("مسلسل") || cat.contains("مسلسلات") || cat.contains("برامج") ||
                cat.contains("برنامج") -> TvType.TvSeries

            cat.contains("افلام") || cat.contains("أفلام") || cat.contains("فيلم") -> TvType.Movie
            href.contains("/episode/") || href.contains("/season/") ||
                href.contains("/serie/") -> TvType.TvSeries

            else -> TvType.Movie
        }
    }

    /** Parse one content card anchor into a search response. */
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        // Skip taxonomy / utility links that are not content posts.
        if (href.contains("/type/") || href.contains("/category/") ||
            href.contains("/year/") || href.contains("/quality/") ||
            href.contains("/country/") || href.contains("/language/")
        ) return null

        val titleEl = selectFirst("h1.BottomTitle") ?: selectFirst(".BottomTitle")
        val title = (titleEl?.text() ?: attr("title")).trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        val catName = selectFirst("span.cat_name")?.text()
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()

        return when (detectType(catName, href)) {
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    /** All unique content cards on a listing page (home / category / search). */
    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("li.movieItem > a, a:has(.BottomTitle)").forEach { a ->
            val item = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(item.url, item)
        }
        return seen.values.toList()
    }

    private fun Document.pageTitle(): String? =
        selectFirst(".singleTitle span em")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst(".singleTitle")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("h1")?.text()?.trim()?.ifBlank { null }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst(".single-thumbnail img")?.attr("src"))
            ?: fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))

    private fun Document.pagePlot(): String? =
        selectFirst(".extra-content p")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst(".singleStory")?.text()?.trim()?.ifBlank { null }

    private fun Document.pageYear(): Int? =
        selectFirst(".LeftBox a[href*=\"/year/\"]")?.text()?.trim()?.toIntOrNull()

    private fun Document.pageTags(): List<String> =
        select(".LeftBox a[href*=\"/type/\"]").map { it.text().trim() }.filter { it.isNotBlank() }

    private fun episodeNumberOf(href: String, text: String): Int? {
        Regex("""e(\d{1,4})""").find(href.substringAfter("/episode/", ""))?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return Regex("""\d{1,4}""").findAll(text).lastOrNull()?.value?.toIntOrNull()
    }

    private fun seasonNumberOf(href: String, text: String = ""): Int? {
        Regex("""-s(\d{1,3})""").find(href)?.let { return it.groupValues[1].toIntOrNull() }
        // Arabic season posts carry the number in the slug/title instead:
        // a plain digit ("الموسم 2") or an ordinal word ("الموسم الرابع").
        val decoded = runCatching {
            java.net.URLDecoder.decode("$href $text", "UTF-8")
        }.getOrDefault(href)
        Regex("""الموسم\s*(\d{1,3})""").find(decoded)?.let { return it.groupValues[1].toIntOrNull() }
        if (decoded.contains("الموسم")) {
            val ordinals = listOf(
                "الحادي عشر" to 11, "الثاني عشر" to 12,
                "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3,
                "الرابع" to 4, "الخامس" to 5, "السادس" to 6, "السابع" to 7,
                "الثامن" to 8, "التاسع" to 9, "العاشر" to 10
            )
            for ((word, n) in ordinals) if (decoded.contains(word)) return n
        }
        return null
    }

    /** True for URLs on this site's own domain. The site's header menu
     *  links /season/ and /serie/ archives on the brand's old domain
     *  (egydead.com); those must never be mistaken for a post's own
     *  season/series links. */
    private fun isSiteUrl(u: String): Boolean = u.startsWith(mainUrl)

    /** The parent series page ("all seasons") linked from a season page, or
     *  null when the post has none. Bare archive links are skipped. */
    private fun Document.serieParentUrl(): String? =
        select("a[href*=\"/serie/\"]")
            .map { fixUrl(it.attr("href")) }
            .firstOrNull {
                isSiteUrl(it) && it.contains("/serie/") &&
                    it.substringAfter("/serie/").isNotBlank()
            }

    /** Episode links listed on a season page, sorted ascending by episode number. */
    private fun Document.parseEpisodes(seasonNumber: Int?): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()
        select("a[href*=\"/episode/\"]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (!isSiteUrl(href)) return@forEach
            if (!href.contains("/episode/")) return@forEach
            if (href.substringAfter("/episode/").isBlank()) return@forEach
            val epNum = episodeNumberOf(href, a.text())
            val ep = newEpisode(href, {
                this.name = a.text().trim().ifBlank { epNum?.let { n -> "الحلقة $n" } }
                this.season = seasonNumber
                this.episode = epNum
            })
            byUrl.putIfAbsent(href, ep)
        }
        return byUrl.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    /**
     * Fetches a site page as a parsed document. Since 2026-10-17 the site
     * sits behind a Cloudflare fingerprint wall that refuses the app's
     * direct requests (403 "Just a moment"), so pages are fetched through
     * the relay's Chrome-impersonating /relay/page route first; the direct
     * fetch remains as a fallback for the day the wall lifts.
     */
    private suspend fun fetchDocument(url: String): Document {
        val relayUrl = "https://egydead-stremio.onrender.com/relay/page?url=" +
            java.net.URLEncoder.encode(url, "UTF-8")
        for (attempt in 1..2) {
            val text = runCatching { app.get(relayUrl).text }.getOrNull()
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            val html = json?.optString("text")?.takeIf { it.isNotBlank() }
            if (json?.optBoolean("ok") == true && html != null) {
                return org.jsoup.Jsoup.parse(html, url)
            }
            if (attempt == 1) Thread.sleep(2000)
        }
        return app.get(url, headers = browserHeaders).document
    }

    // ---------- MainAPI ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Verified pagination pattern: {base}?page={n}/ — it holds for EVERY
        // section (home feed, /page/movies/, /category/, /series-category/
        // and /type/ archives alike; page 2+ returns fresh cards, e.g.
        // /type/action/ runs to 100+ pages). v9: all sections paginate;
        // v8 and earlier wrongly reported hasNext=false for the /type/ and
        // other unlisted sections, truncating them to their first page.
        val url = if (page <= 1) request.data else "${request.data}?page=$page/"
        val document = fetchDocument(url)
        val items = document.parseCards()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return fetchDocument(url).parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url)

        return when {
            url.contains("/season/") -> {
                // v11: a season post is one slice of a show. When the season
                // page links its parent series page ("all seasons"), open the
                // series instead so the user gets EVERY season's episodes
                // stacked in one list, not just this season's. Posts with no
                // series page (single-post complete shows) keep the plain
                // season list below, which is already complete for them.
                val serieUrl = document.serieParentUrl()
                if (serieUrl != null && serieUrl != url) {
                    load(serieUrl)
                } else {
                    val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, document.parseEpisodes(seasonNumberOf(url))) {
                        this.posterUrl = document.pagePoster()
                        this.plot = document.pagePlot()
                        this.year = document.pageYear()
                        this.tags = document.pageTags()
                    }
                }
            }

            url.contains("/serie/") -> {
                val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                val seasonLinks = LinkedHashMap<String, Int?>()
                // Seasons are listed on the series page as cards; prefer card
                // anchors so sidebar/feed links of other shows cannot leak
                // in, and fall back to the plain sweep only when no card
                // matches.
                val seasonAnchors = document
                    .select("li.movieItem > a[href*=\"/season/\"], a:has(.BottomTitle)[href*=\"/season/\"]")
                    .ifEmpty { document.select("a[href*=\"/season/\"]") }
                seasonAnchors.forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (!isSiteUrl(href)) return@forEach
                    if (!href.contains("/season/")) return@forEach
                    if (href.contains("/feed")) return@forEach
                    if (href.substringAfter("/season/").isBlank()) return@forEach
                    seasonLinks.putIfAbsent(href, seasonNumberOf(href, a.text()))
                }
                val episodes = mutableListOf<Episode>()
                if (seasonLinks.isEmpty()) {
                    episodes += document.parseEpisodes(1)
                } else {
                    seasonLinks.entries.forEachIndexed { index, (seasonUrl, seasonNum) ->
                        val seasonDoc = runCatching { fetchDocument(seasonUrl) }.getOrNull()
                            ?: return@forEachIndexed
                        episodes += seasonDoc.parseEpisodes(seasonNum ?: (index + 1))
                    }
                }
                newTvSeriesLoadResponse(
                    title, url, TvType.TvSeries,
                    episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
                ) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                    this.tags = document.pageTags()
                }
            }

            url.contains("/episode/") -> {
                // An episode page on this site belongs to a season; jump to the
                // season (or series) page so the user gets the full episode list.
                val seasonUrl = document.select("a[href*=\"/season/\"]")
                    .map { fixUrl(it.attr("href")) }
                    .firstOrNull { isSiteUrl(it) && it.contains("/season/") }
                val serieUrl = document.serieParentUrl()
                when {
                    seasonUrl != null && seasonUrl != url -> load(seasonUrl)
                    serieUrl != null && serieUrl != url -> load(serieUrl)
                    else -> {
                        val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                        val single = newEpisode(url, {
                            this.name = title
                            this.season = seasonNumberOf(url)
                            this.episode = episodeNumberOf(url, title)
                        })
                        newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(single)) {
                            this.posterUrl = document.pagePoster()
                            this.plot = document.pagePlot()
                            this.year = document.pageYear()
                            this.tags = document.pageTags()
                        }
                    }
                }
            }

            else -> {
                val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                    this.tags = document.pageTags()
                }
            }
        }
    }

    /**
     * Asks the relay server (the Stremio addon service) to perform the watch
     * unlock for [data] server-side and feeds what it returns — the MegaMax
     * mirror list, else the raw embeds — to the built-in extractors.
     * Returns true when at least one playable link was emitted.
     */
    private suspend fun loadViaRelay(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val relayUrl = "https://egydead-stremio.onrender.com/relay/egydead?url=" +
            java.net.URLEncoder.encode(data, "UTF-8")
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(relayUrl).text }.getOrNull()
            if (text != null) break
            Thread.sleep(2000)
        }
        val body = text ?: run {
            diag += "relay:fetch-fail"
            return false
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: run {
            diag += "relay:bad-json"
            return false
        }
        if (!json.optBoolean("ok")) {
            diag += "relay:not-ok"
            return false
        }
        var found = false
        val mirrors = json.optJSONArray("mirrors")
        diag += "relay:mirrors=${mirrors?.length() ?: 0}"
        if (mirrors != null) {
            for (i in 0 until mirrors.length()) {
                val link = mirrors.optString(i)
                if (link.isNotBlank() && loadExtractor(link, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        if (!found) {
            val relayEmbeds = json.optJSONArray("embeds")
            diag += "relay:embeds=${relayEmbeds?.length() ?: 0}"
            if (relayEmbeds != null) {
                for (i in 0 until relayEmbeds.length()) {
                    val embed = relayEmbeds.optString(i)
                    if (embed.isBlank()) continue
                    if (loadExtractor(embed, data, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                    if (loadFromMegaMax(embed, data, subtitleCallback, callback, diag)) {
                        found = true
                    }
                }
            }
        }
        if (found) diag += "relay:hit"
        return found
    }

    private fun collectEmbeds(document: Document, into: LinkedHashSet<String>) {
        document.select(".serversList li").forEach { li ->
            li.attr("data-link").trim().takeIf { it.isNotBlank() }?.let { into.add(fixUrl(it)) }
        }
        document.selectFirst(".watchAreaMaster .holder iframe")?.attr("src")
            ?.trim()?.takeIf { it.isNotBlank() }?.let { into.add(fixUrl(it)) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embeds = LinkedHashSet<String>()
        // Field diagnostics: each stage appends a short marker; if nothing
        // playable is found, the markers are surfaced as one debug source so
        // the exact failing stage can be read off the player's source list.
        val diag = mutableListOf<String>()

        // v8: server-side relay first. The phone's own requests to the site
        // pass the CDN (HTTP 200) but receive a watch page stripped of its
        // server list (observed on-device: warm=200 | post=200 | embeds=0),
        // while the identical flow succeeds from the relay server. The relay
        // (free tier) can be asleep, so one retry is allowed inside
        // loadViaRelay before falling back to the local chain below.
        if (loadViaRelay(data, subtitleCallback, callback, diag)) return true

        // v7: visit the page first like a real browser (this also picks up
        // the CDN's bot-management cookie for the session), then unlock the
        // watch view with the View=1 POST. The CDN's bot score is
        // probabilistic, so the POST is retried a few times before giving
        // up; the HTTP statuses are recorded in the diagnostics.
        val warm = runCatching { app.get(data, headers = browserHeaders) }
        diag += "warm=${warm.getOrNull()?.code ?: "err"}"

        var postCode = -1
        for (attempt in 1..4) {
            val doc = runCatching {
                val res = app.post(data, data = mapOf("View" to "1"), headers = browserHeaders, referer = data)
                postCode = res.code
                res.document
            }.getOrNull()
            if (doc != null) collectEmbeds(doc, embeds)
            if (embeds.isNotEmpty()) break
            if (attempt < 4) Thread.sleep(800)
        }
        diag += "post=$postCode"

        if (embeds.isEmpty()) {
            runCatching { app.get(data, headers = browserHeaders).document }.getOrNull()?.let { collectEmbeds(it, embeds) }
        }
        diag += "embeds=${embeds.size}"
        if (embeds.isEmpty()) {
            emitDebug(diag, callback)
            return true
        }

        var found = false
        for (embed in embeds) {
            if (loadExtractor(embed, data, subtitleCallback, callback)) {
                found = true
                diag += "direct-extractor:hit"
                continue
            }
            // The site's default host ("MegaMax") has no built-in extractor: its
            // player is an Inertia app whose deferred "streams" prop lists the
            // real mirrors on well-known hosts. Resolve those instead.
            if (loadFromMegaMax(embed, data, subtitleCallback, callback, diag)) {
                found = true
                continue
            }

            // Last resort: scan the embed page directly for a playable file.
            val pageText = runCatching { app.get(embed, headers = browserHeaders, referer = data).text }.getOrNull()
            if (pageText != null) {
                val playlist = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageText)?.value
                val file = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(pageText)?.value
                when {
                    playlist != null -> {
                        callback(
                            newExtractorLink(name, name, playlist, ExtractorLinkType.M3U8) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                    file != null -> {
                        callback(
                            newExtractorLink(name, name, file, ExtractorLinkType.VIDEO) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }
            diag += "scan:miss"
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
     * Resolves a "MegaMax" embed (the /iframe/{id} page the site uses as its
     * default server). The page is an Inertia/Vue app: the initial HTML only
     * carries video metadata plus an asset "version"; the actual sources live
     * in a deferred "streams" prop that the player fetches with an Inertia
     * partial reload. That prop lists, per quality, mirror links on well-known
     * hosts (Voe, VidMoly, MixDrop, Doodstream, StreamWish, Uqload, VidHide,
     * ...) — all understood by CloudStream's built-in extractors.
     * Verified live against the real host on 2026-10-15.
     */
    private suspend fun loadFromMegaMax(
        embed: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val pageText = runCatching { app.get(embed, headers = browserHeaders, referer = referer).text }.getOrNull()
        if (pageText == null) {
            diag += "mm-page:fetch-fail"
            return false
        }
        val pageJsonText = Regex(
            """<script[^>]*data-page[^>]*>(\{.*?\})</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(pageText)?.groupValues?.get(1)
        if (pageJsonText == null) {
            diag += "mm-json:missing"
            return false
        }
        val pageJson = runCatching { JSONObject(pageJsonText) }.getOrNull()
        if (pageJson == null) {
            diag += "mm-json:parse-fail"
            return false
        }
        val version = pageJson.optString("version")
        if (version.isBlank()) {
            diag += "mm-version:blank"
            return false
        }
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
        }.getOrNull()
        if (streamsText == null) {
            diag += "mm-streams:fetch-fail"
            return false
        }

        val streams = runCatching {
            JSONObject(streamsText).getJSONObject("props").getJSONObject("streams")
        }.getOrNull()
        if (streams == null) {
            diag += "mm-streams:parse-fail"
            return false
        }
        if (streams.optString("status") != "success") {
            diag += "mm-streams:${streams.optString("status").ifBlank { "?" }}"
            return false
        }
        val qualities = streams.optJSONArray("data")
        if (qualities == null) {
            diag += "mm-data:missing"
            return false
        }

        var mirrorCount = 0
        var hits = 0
        var found = false
        for (i in 0 until qualities.length()) {
            val mirrors = qualities.optJSONObject(i)?.optJSONArray("mirrors") ?: continue
            for (j in 0 until mirrors.length()) {
                var link = mirrors.optJSONObject(j)?.optString("link").orEmpty()
                if (link.startsWith("//")) link = "https:$link"
                if (link.isBlank()) continue
                mirrorCount++
                if (loadExtractor(link, embed, subtitleCallback, callback)) {
                    found = true
                    hits++
                }
            }
        }
        diag += "mm-mirrors=$mirrorCount"
        diag += "mm-hits=$hits"
        return found
    }
}
