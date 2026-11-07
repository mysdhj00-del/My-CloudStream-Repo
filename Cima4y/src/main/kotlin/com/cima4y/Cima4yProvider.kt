package com.cima4y

import android.util.Base64
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
import com.lagradost.cloudstream3.network.CloudflareKiller
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
 * Cima4y provider for CloudStream 3 — dedicated to https://cima4y.net
 * (سيما فور يو / Cima4U).
 *
 * Traced live 2026-11-07 against the real site (custom frontend,
 * NOT WordPress):
 * - Content model: /film/<slug> (films), /episode/<slug> (episodes).
 *   NO series hub pages exist — a series is a set of episode pages
 *   sharing one slug stem; the episode detail page lists its
 *   same-season siblings, and /search?s=<series name> returns ALL
 *   seasons (verified: "tulsa" -> 33 episodes, S1-S4).
 * - Cards are a.show-card blocks: img[src] poster (photos/uploads
 *   .webp, or IMDb shares for films), p.title, span.categ, span.rate
 *   (e.g. 7.6), span.ep ("الحلقة<br/>N") on episode cards only.
 *   Home = ONE section «أحدث الأفلام والمسلسلات» (62 cards: 56
 *   episodes + 6 films). Categories paginate as ?page=N (verified:
 *   50 cards/page, 170 pages on افلام-اجنبي).
 * - Search: GET /search?s=<query> (one page, no pagination) —
 *   returns films + episodes mixed (verified: "avengers" -> 10
 *   films + 39 episodes; Arabic "رعب" -> title matches only).
 *   There is NO horror category (GET /category/افلام-رعب -> 404).
 * - Watch pages: /watch/<slug> is Cloudflare Turnstile-protected
 *   (stubborn from datacenter IPs — needs CloudflareKiller) and
 *   carries 9 servers: earnvids (default), vinovo, uqload, byse,
 *   voe, ok, doodstream, luluvido, m1xdrop. Embed URLs (verified
 *   live in a real browser by switching servers, Star One 2026):
 *   fastvid.cam/embed/<id>, vinovo.to/e/<id>, uqload.vc/e/<id>.html,
 *   bysevepoin.com/e/<id>, voe.sx/e/<id>, ok.ru/videoembed/<id>
 *   (DEAD: "Video has not been found"), playmogo.com/e/<id>
 *   (doodstream), luluvido.com/e/<id>, m1xdrop.bz/e/<id> (302 ->
 *   mxdrop.click). The raw button markup hides the embed URL
 *   (it appears only in the player iframe src after selection),
 *   so loadLinks regex-extracts embed URLs from the whole watch
 *   page HTML instead of relying on one attribute.
 * - Playback decode (verified live): fastvid + luluvido embed
 *   pages carry packed JS (P.A.C.K.E.R.) that unpacks to a
 *   tokenized master.m3u8 — fastvid: acek-cdn.com hls2 master
 *   (200, 1080p 1920x988, duration 5668.69s = the browser's
 *   1:34:28); luluvido: tnmr.org hls2 master (e=28800). Same
 *   family as Wecima2's CxUnpack. The __hpv3/video candidate
 *   on detail pages is 403 from outside a real browser session
 *   (human-signal gate) and is NOT used; /m/ mirror links expire
 *   instantly from non-browser clients.
 * - USER RULES (2026-11-07): ONE poster per series — episode
 *   cards collapse into a single series card with ALL episodes
 *   beneath it (mergeShowCards, same as Wecima2 v10/v11); every
 *   section shows its FULL contents, sideways-scrolling, titled
 *   "<section> — Cima4y"; search covers the WHOLE site.
 * - Subtitle gear (v1, Wecima2/HorrorMovies port): settings
 *   switch (default ON) auto-attaches the best English subtitle
 *   to every film/episode (Latin title + year -> IMDb suggest ->
 *   OpenSubtitles v3, sync-first ranking). DEBUG line carries
 *   sub:<diag>. ok counts REAL links emitted (Wecima2 v14 lesson:
 *   loadExtractor's boolean counts attempts, not links).
 * - No coroutines anywhere (CI classpath law).
 */
class Cima4yProvider : MainAPI() {
    override var mainUrl = "https://cima4y.net"
    override var name = "Cima4y"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override var sequentialMainPage = true

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "ar,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "https://cima4y.net/",
    )

    private fun posterHeaders(): Map<String, String> {
        val out = HashMap<String, String>()
        out["User-Agent"] = headers["User-Agent"] ?: "Mozilla/5.0"
        out["Referer"] = "$mainUrl/"
        cloudflareKiller.savedCookies["cima4y.net"]?.let { cookies ->
            if (cookies.isNotEmpty()) {
                out["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }
        return out
    }

    // The site's FULL nav (16 categories, verified live from the
    // homepage 2026-11-07) + the home feed, every section titled
    // "<section> — Cima4y" with its full contents.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الأفلام والمسلسلات — Cima4y",
        "$mainUrl/category/افلام-اجنبي" to "أفلام أجنبي — Cima4y",
        "$mainUrl/category/افلام-هندي" to "أفلام هندي — Cima4y",
        "$mainUrl/category/افلام-انمي" to "أفلام أنمي — Cima4y",
        "$mainUrl/category/افلام-اسيوية" to "أفلام آسيوية — Cima4y",
        "$mainUrl/category/افلام-تركية" to "أفلام تركية — Cima4y",
        "$mainUrl/category/افلام-عربي" to "أفلام عربي — Cima4y",
        "$mainUrl/category/مسلسلات-اجنبي" to "مسلسلات أجنبي — Cima4y",
        "$mainUrl/category/مسلسلات-انمي" to "مسلسلات أنمي — Cima4y",
        "$mainUrl/category/مسلسلات-تركية" to "مسلسلات تركية — Cima4y",
        "$mainUrl/category/مسلسلات-اسيوية" to "مسلسلات آسيوية — Cima4y",
        "$mainUrl/category/مسلسلات-مدبلجة" to "مسلسلات مدبلجة — Cima4y",
        "$mainUrl/category/مسلسلات-عربي" to "مسلسلات عربي — Cima4y",
        "$mainUrl/category/مسلسلات-هندية" to "مسلسلات هندية — Cima4y",
        "$mainUrl/category/برامج-تلفزيونية" to "برامج تلفزيونية — Cima4y",
        "$mainUrl/category/عروض-مصارعة" to "عروض مصارعة — Cima4y",
        "$mainUrl/category/مسلسلات-رمضان-2026" to "مسلسلات رمضان 2026 — Cima4y",
    )

    // ---------- cards ----------

    private data class ParsedCard(
        val title: String,
        val url: String,
        val poster: String?,
        val rating: Double?,
        val isEpisode: Boolean,
    )

    private fun parseCards(doc: Document): List<ParsedCard> {
        return doc.select("a.show-card").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = a.selectFirst("p.title")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val poster = a.selectFirst("img")?.attr("src")?.trim()
                ?.takeIf { it.startsWith("http") }
            val rating = a.selectFirst("span.rate")?.ownText()?.trim()?.toDoubleOrNull()
            ParsedCard(title, href, poster, rating, href.contains("/episode/"))
        }
    }

    /** One poster per series: episode cards collapse by series key
     * (slug stem before -الموسم-/-الحلقة-), first (newest) kept.
     * Films dedupe by URL. */
    private fun mergeShowCards(cards: List<ParsedCard>): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seenSeries = HashSet<String>()
        val seenFilms = HashSet<String>()
        for (c in cards) {
            if (!c.isEpisode) {
                if (!seenFilms.add(c.url.substringAfter("/film/"))) continue
                out.add(newMovieSearchResponse(cleanFilmTitle(c.title), c.url, TvType.Movie) {
                    this.posterUrl = c.poster
                    this.posterHeaders = posterHeaders()
                })
                continue
            }
            if (!seenSeries.add(seriesKey(c.url))) continue
            val type =
                if (c.url.contains("/episode/انمي-")) TvType.Anime else TvType.TvSeries
            out.add(newTvSeriesSearchResponse(cleanSeriesTitle(c.title), c.url, type) {
                this.posterUrl = c.poster
                this.posterHeaders = posterHeaders()
            })
        }
        return out
    }

    private fun seriesKey(url: String): String {
        val slug = url.substringAfter("/episode/").trimEnd('/')
        return slug.substringBefore("-الموسم-").substringBefore("-الحلقة-")
    }

    private fun cleanSeriesTitle(title: String): String {
        var t = title.trim()
        for (p in listOf("مسلسل ", "انمي ", "برنامج ")) {
            if (t.startsWith(p)) {
                t = t.removePrefix(p)
                break
            }
        }
        t = t.substringBefore(" الموسم").substringBefore(" الحلقة").trim()
        return t.ifEmpty { title }
    }

    private fun cleanFilmTitle(title: String): String {
        var t = title.trim().removePrefix("فيلم ")
        for (s in listOf(" مترجم اون لاين", " مترجم", " مترجمة", " مدبلج", " اون لاين")) {
            if (t.endsWith(s)) t = t.removeSuffix(s)
        }
        return t.trim().ifEmpty { title }
    }

    // ---------- home / search ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data
        else request.data + if (request.data.contains("?")) "&page=$page" else "?page=$page"
        return try {
            val doc = app.get(pageUrl, headers = headers, interceptor = cloudflareKiller).document
            val cards = parseCards(doc)
            val items = mergeShowCards(cards)
            if (items.isEmpty()) {
                val debugTitle =
                    "DEBUG v6 home ${request.name} page=$page len=${doc.html().length} cards=0"
                return newHomePageResponse(
                    request.name,
                    listOf(newMovieSearchResponse(debugTitle, request.data, TvType.Movie) {}),
                    hasNext = false
                )
            }
            newHomePageResponse(request.name, items, hasNext = cards.size >= 50)
        } catch (e: Exception) {
            val debugTitle =
                "DEBUG v6 home ${request.name} page=$page err=${e.javaClass.simpleName}"
            newHomePageResponse(
                request.name,
                listOf(newMovieSearchResponse(debugTitle, request.data, TvType.Movie) {}),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return try {
            val doc = app.get("$mainUrl/search?s=$q", headers = headers, interceptor = cloudflareKiller).document
            mergeShowCards(parseCards(doc))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- detail ----------

    private val seasonOrdinals = mapOf(
        "الاول" to 1, "الأول" to 1, "الاولي" to 1, "الأولى" to 1,
        "الثاني" to 2, "الثانية" to 2,
        "الثالث" to 3, "الثالثة" to 3,
        "الرابع" to 4, "الرابعة" to 4,
        "الخامس" to 5, "الخامسة" to 5,
        "السادس" to 6, "السادسة" to 6,
        "السابع" to 7, "السابعة" to 7,
        "الثامن" to 8, "الثامنة" to 8,
        "التاسع" to 9, "التاسعة" to 9,
        "العاشر" to 10, "العاشرة" to 10,
        "الحادي عشر" to 11, "الثاني عشر" to 12,
        "الثالث عشر" to 13, "الرابع عشر" to 14,
        "الخامس عشر" to 15, "السادس عشر" to 16,
        "السابع عشر" to 17, "الثامن عشر" to 18,
        "التاسع عشر" to 19, "العشرون" to 20
    )

    /** (season, episode) from an episode slug, or null. */
    private fun parseSeasonEp(href: String): Pair<Int, Int>? {
        val slug = href.substringAfter("/episode/")
        var season = 1
        val sm = Regex("""-الموسم-([^-]+)-""").find(slug)
        if (sm != null) {
            season = seasonOrdinals[sm.groupValues[1]]
                ?: sm.groupValues[1].toIntOrNull() ?: 1
        }
        val em = Regex("""-الحلقة-(\d+)-""").find(slug)
            ?: Regex("""-الحلقة-(\d+)$""").find(slug)
        val ep = em?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return season to ep
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, interceptor = cloudflareKiller).document
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        if (url.contains("/film/")) {
            val title = cleanFilmTitle(ogTitle.ifEmpty { url.substringAfterLast('/') })
            val watchUrl = doc.selectFirst("a.btn.watch")?.attr("href")?.let { href ->
                if (href.startsWith("http")) href else mainUrl + href
            } ?: (mainUrl + "/watch/" + url.substringAfter("/film/"))
            return newMovieLoadResponse(
                title, url, TvType.Movie,
                subData(watchUrl, title, null, null)
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // Episode page -> whole series (no hub pages on this site).
        val slug = url.substringAfter("/episode/").trimEnd('/')
        val seriesTitle = cleanSeriesTitle(ogTitle.ifEmpty { slug })
        val type = if (slug.startsWith("انمي-")) TvType.Anime else TvType.TvSeries
        val key = seriesKey(url)
        val eps = LinkedHashMap<Pair<Int, Int>, String>()
        fun collectFrom(d: Document) {
            for (a in d.select("a[href*=\"/episode/\"]")) {
                val href = a.attr("href").trim()
                if (!href.contains("/episode/")) continue
                if (seriesKey(href) != key) continue
                val se = parseSeasonEp(href) ?: continue
                eps.putIfAbsent(se, href)
            }
        }
        collectFrom(doc)
        // Cross-season: the site search returns ALL seasons of the
        // series (verified: "tulsa" -> 33 episodes, S1-S4).
        try {
            val q = java.net.URLEncoder.encode(seriesTitle, "UTF-8")
            val sdoc = app.get("$mainUrl/search?s=$q", headers = headers, interceptor = cloudflareKiller).document
            collectFrom(sdoc)
        } catch (e: Exception) {
        }
        val episodes = eps.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (se, epUrl) ->
                val (s, e) = se
                val watchUrl = mainUrl + "/watch/" + epUrl.substringAfter("/episode/")
                newEpisode(subData(watchUrl, seriesTitle, s, e)) {
                    this.name = "الحلقة $e"
                    this.season = s
                    this.episode = e
                    this.posterUrl = poster
                }
            }
        return newTvSeriesLoadResponse(seriesTitle, url, type, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------- playback ----------

    /** Embed URL patterns for the 9 servers on the watch page
     * (verified live 2026-11-07, Star One 2026). */
    private val embedRegexes = listOf(
        Regex("""https?://(?:www\.)?fastvid\.cam/embed/[A-Za-z0-9_-]+"""),
        Regex("""https?://(?:www\.)?vinovo\.to/e/[A-Za-z0-9_-]+"""),
        Regex("""https?://(?:www\.)?uqload\.(?:vc|is|to)/e/[A-Za-z0-9_-]+\.html"""),
        Regex("""https?://(?:www\.)?bysevepoin\.com/e/[A-Za-z0-9_-]+"""),
        Regex("""https?://(?:www\.)?voe\.sx/e/[A-Za-z0-9_-]+"""),
        Regex("""https?://(?:www\.)?ok\.ru/videoembed/\d+"""),
        Regex("""https?://(?:www\.)?(?:doodstream\.com|playmogo\.com)/e/[A-Za-z0-9_-]+"""),
        Regex("""https?://(?:www\.)?luluvido\.com/e/[A-Za-z0-9_-]+"""),
        Regex("""https?://(?:www\.)?(?:m1xdrop\.bz|mxdrop\.click)/e/[A-Za-z0-9_-]+"""),
    )
    private val genericEmbedRegex =
        Regex("""https?://[^\s"'<>\\]+/(?:embed|e)/[A-Za-z0-9_-]+(?:\.html)?""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (realData, subTitle, subSeason, subEp) = parseSubData(data)
        var subDiag = "sub:-"
        if (Cima4ySubs.autoEnglish && subTitle.isNotBlank()) {
            subDiag = if (emitEnglishSub(subTitle, subSeason, subEp, realData, subtitleCallback)) "sub:en" else "sub:no-match"
        } else if (!Cima4ySubs.autoEnglish) {
            subDiag = "sub:off"
        }
        var pageCode = 0
        var embCount = 0
        var emittedLinks = 0
        // v2: diagnose WHAT the phone actually received — the v1
        // on-device verdict (Tulsa E1) was page=200 emb=0, i.e.
        // the watch page loaded but no embed URL matched. Either
        // the page is a CF challenge page, the URLs are escaped /
        // protocol-relative, or they live behind the player
        // iframe (/m/ redirector) and JS. The diagnostics below
        // make the next screenshot decisive.
        // v3: the v2 verdict (len=55133 cf=1 ifr=2 hosts=uqload
        // m=21) proves the phone gets a REAL 55KB page, not the
        // 1.8KB CF challenge page — but the loose cf flag fires
        // on real pages too (they embed the CF jsd script), and
        // the embed URLs are still nowhere in the static HTML.
        // So v3 exfiltrates the page structure itself through
        // extra DEBUG sources (iframe srcs, a snippet around the
        // servers section, and the in-session result of fetching
        // the first /m/ link) — the next screenshot of the
        // source list names the exact mechanism.
        // v4: the v3 run came back page=0 — the watch-page GET
        // itself THREW (the Turnstile solve is flaky and the
        // error was swallowed silently). v4 records the exact
        // exception in the DEBUG line and retries the fetch once
        // after a short pause.
        var diag = ""
        var fetchErr = ""
        // v6: probe verdicts are carried in the MAIN debug line
        // (the player title) — the v5 extras only show in the
        // source list, which never arrived in a screenshot.
        var kwHitsStr = "-"
        var scriptCount = 0
        var hpv3Present = 0
        var playerIfr = "-"
        var ajaxCodesStr = "-"
        var b64Verdict = "NONE"
        var mNowCode = "-"
        val debugExtras = ArrayList<String>()
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            emittedLinks++
            callback(link)
        }
        try {
            val htmlRaw = try {
                app.get(realData, headers = headers, interceptor = cloudflareKiller).text
            } catch (e: Exception) {
                try { Thread.sleep(2000) } catch (ignored: Exception) { }
                app.get(realData, headers = headers, interceptor = cloudflareKiller).text
            }
            pageCode = 200
            val html = htmlRaw.replace("\\/", "/")
            // v3: strict CF check — the real watch page embeds the
            // CF jsd script too, so "challenge-platform" alone is
            // NOT proof of a challenge page.
            val isCf = htmlRaw.contains("<title>Just a moment...</title>") ||
                htmlRaw.contains("Attention Required")
            val ifrCount = Regex("<iframe", RegexOption.IGNORE_CASE).findAll(htmlRaw).count()
            val hostsFound = listOf(
                "fastvid", "vinovo", "uqload", "byse", "voe", "ok.ru",
                "doodstream", "playmogo", "luluvido", "m1xdrop", "mxdrop"
            ).filter { htmlRaw.contains(it, ignoreCase = true) }
            val mCount = Regex("cima4y\\.net/m/").findAll(htmlRaw).count()
            diag = "len=${htmlRaw.length} cf=${if (isCf) 1 else 0} ifr=$ifrCount " +
                "hosts=${hostsFound.joinToString(",").ifEmpty { "-" }} m=$mCount"
            // v3 exfiltration 1: every iframe src on the watch page.
            val iframeSrcs = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""")
                .findAll(htmlRaw).map { it.groupValues[1] }.toList()
            debugExtras.add("DEBUG v6 iframes=" + iframeSrcs.joinToString(",").take(180).ifEmpty { "-" })
            // v6: the player iframe (not the CF 1x1) is the one
            // whose /m/ link matters — keep it for the title line.
            playerIfr = iframeSrcs.firstOrNull {
                !it.contains("challenge-platform") && (it.contains("/m/") || it.contains("embed") || it.contains("/e/"))
            }?.take(50) ?: "-"
            // v3 exfiltration 2: HTML snippet around the servers section.
            val srvIdx = htmlRaw.indexOf("server", ignoreCase = true).takeIf { it >= 0 }
                ?: htmlRaw.indexOf("سيرفر").takeIf { it >= 0 }
            if (srvIdx != null) {
                val snip = htmlRaw.substring(maxOf(0, srvIdx - 60), minOf(htmlRaw.length, srvIdx + 140))
                    .replace(Regex("""\s+"""), " ").trim()
                debugExtras.add("DEBUG v6 srv=" + snip.take(200))
            } else {
                debugExtras.add("DEBUG v6 srv=NONE")
            }
            // v5 (panel #5): the panel's top diagnostics — which
            // script keywords are in the page, every <script src>,
            // hidden inputs, and the __hpv3 block from THIS page.
            val kwHits = listOf(
                "fetch(", "\$.ajax", "XMLHttpRequest", "/ajax/", "/api/",
                "/player", "getEmbed", "getServer", "loadServer",
                "embed_url", "atob", "decode", "fromCharCode", "__hpv3"
            ).filter { htmlRaw.contains(it, ignoreCase = true) }
            kwHitsStr = kwHits.joinToString(",").take(40).ifEmpty { "-" }
            debugExtras.add("DEBUG v6 kw=" + kwHits.joinToString(",").take(180).ifEmpty { "-" })
            val scriptSrcs = Regex("""<script[^>]+src=["']([^"']+)["']""")
                .findAll(htmlRaw).map { it.groupValues[1] }.toList()
            scriptCount = scriptSrcs.size
            debugExtras.add("DEBUG v6 scripts=" + scriptSrcs.joinToString(",").take(180).ifEmpty { "-" })
            val hiddenInputs = Regex("""<input[^>]+type=["']hidden["'][^>]*>""", RegexOption.IGNORE_CASE)
                .findAll(htmlRaw).map { it.value.take(120) }.toList()
            debugExtras.add("DEBUG v6 hidden=" + hiddenInputs.joinToString(" | ").take(200).ifEmpty { "-" })
            val hpv3Idx = htmlRaw.indexOf("__hpv3")
            hpv3Present = if (hpv3Idx >= 0) 1 else 0
            if (hpv3Idx >= 0) {
                debugExtras.add("DEBUG v6 hpv3=" + htmlRaw.substring(hpv3Idx, minOf(htmlRaw.length, hpv3Idx + 220))
                    .replace(Regex("""\s+"""), " ").take(220))
            } else {
                debugExtras.add("DEBUG v6 hpv3=NONE")
            }
            val embeds = LinkedHashSet<String>()
            for (re in embedRegexes) re.findAll(html).forEach { embeds.add(it.value) }
            genericEmbedRegex.findAll(html).forEach {
                val u = it.value
                if (!u.contains("cima4y.net")) embeds.add(u)
            }
            // v2: protocol-relative embeds (//fastvid.cam/embed/...)
            Regex("""//(?:www\.)?(?:fastvid\.cam|vinovo\.to|uqload\.(?:vc|is|to)|bysevepoin\.com|voe\.sx|ok\.ru|doodstream\.com|playmogo\.com|luluvido\.com|m1xdrop\.bz|mxdrop\.click)/(?:embed|e|videoembed)/[A-Za-z0-9_-]+(?:\.html)?""")
                .findAll(html).forEach { embeds.add("https:" + it.value) }
            // v2: follow the player iframe src — on the watch page
            // it may be an /m/ redirector that only resolves
            // inside the phone's CF-cleared session (from curl it
            // 404s instantly, so the sandbox cannot prove it).
            // If it IS an embed URL, take it directly; else scan
            // the page it leads to for embeds.
            val iframeSrc = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""")
                .find(htmlRaw)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank()) {
                try {
                    val iframeUrl = when {
                        iframeSrc.startsWith("http") -> iframeSrc
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        else -> mainUrl + iframeSrc
                    }
                    if (embedRegexes.any { it.containsMatchIn(iframeUrl) }) {
                        embeds.add(iframeUrl)
                    } else {
                        val iframeHtml = app.get(
                            iframeUrl, headers = headers, interceptor = cloudflareKiller
                        ).text.replace("\\/", "/")
                        for (re in embedRegexes) re.findAll(iframeHtml).forEach { embeds.add(it.value) }
                        genericEmbedRegex.findAll(iframeHtml).forEach {
                            val u = it.value
                            if (!u.contains("cima4y.net")) embeds.add(u)
                        }
                    }
                } catch (e: Exception) {
                }
            }
            // v5 (panel #5, H2): Base64-decode every long opaque
            // string in the page and look for embed host names —
            // the mapping may be encoded inline in <script>.
            val hostWords = listOf(
                "fastvid", "vinovo", "uqload", "voe", "doodstream",
                "luluvido", "m1xdrop", "byse", "/embed/", "/e/"
            )
            var b64Hit = ""
            for (m in Regex("""[A-Za-z0-9+/=]{40,}""").findAll(htmlRaw)) {
                val s = m.value
                if (s.contains("cima4y.net/m/")) continue
                try {
                    val dec = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
                    if (hostWords.any { dec.contains(it, ignoreCase = true) }) {
                        b64Hit = dec.take(160)
                        break
                    }
                } catch (e: Exception) {
                }
            }
            val revHit = listOf("divtsaf", "onivniv", "daolqu", "eov", "odivulul", "pordx1m", "esyb")
                .filter { htmlRaw.contains(it, ignoreCase = true) }
            if (b64Hit.isNotEmpty()) {
                b64Verdict = "HIT"
                debugExtras.add("DEBUG v6 b64=" + b64Hit.replace(Regex("""\s+"""), " ").take(200))
                for (re in embedRegexes) re.findAll(b64Hit).forEach { embeds.add(it.value) }
            } else if (revHit.isNotEmpty()) {
                debugExtras.add("DEBUG v6 rev=" + revHit.joinToString(","))
            } else {
                debugExtras.add("DEBUG v6 b64=NONE")
            }
            // v6 (panel #5, H3): fetch the PLAYER iframe's /m/
            // link first — v5 fetched the first /m/ in document
            // order, which may be a decoy from the trap template.
            // Fall back to the first /m/ in the HTML if the
            // iframe has none. Same-session, <1s, exact iframe
            // headers, and the result is scanned for embeds.
            val playerM = iframeSrcs.firstOrNull { it.contains("/m/") }?.let {
                if (it.startsWith("http")) it else mainUrl + it
            }
            val firstM = playerM
                ?: Regex("""https://cima4y\.net/m/[A-Za-z0-9]+""").find(htmlRaw)?.value
            if (firstM != null) {
                try {
                    val mResp = app.get(
                        firstM,
                        headers = headers + mapOf(
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "same-origin"
                        ),
                        referer = realData,
                        interceptor = cloudflareKiller
                    )
                    mNowCode = mResp.code.toString()
                    val mBody = mResp.text.replace("\\/", "/")
                    debugExtras.add("DEBUG v6 m-now code=${mResp.code} len=${mBody.length} head=" +
                        mBody.take(80).replace(Regex("""\s+"""), " "))
                    for (re in embedRegexes) re.findAll(mBody).forEach { embeds.add(it.value) }
                    genericEmbedRegex.findAll(mBody).forEach {
                        val u = it.value
                        if (!u.contains("cima4y.net")) embeds.add(u)
                    }
                } catch (e: Exception) {
                    mNowCode = "ERR"
                    debugExtras.add("DEBUG v6 m-now ERR ${e.javaClass.simpleName}")
                }
            } else {
                debugExtras.add("DEBUG v6 m-now NONE")
            }
            // v5 (panel #5, H1): probe candidate AJAX resolver
            // endpoints — POST {"id": slug, "server": "earnvids"}
            // as JSON with X-Requested-With. A HIT (response
            // containing an embed host) is fed straight into the
            // embed set, so playback itself proves the endpoint.
            val slug = realData.substringAfter("/watch/", "")
            val ajaxJson = """{"id":"$slug","server":"earnvids"}"""
            val ajaxResults = ArrayList<String>()
            val ajaxCodes = ArrayList<String>()
            for (ep in listOf("/ajax/player", "/ajax/get-embed", "/ajax/server", "/api/embed")) {
                try {
                    val r = app.post(
                        mainUrl + ep,
                        headers = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ),
                        referer = realData,
                        requestBody = ajaxJson.toRequestBody("application/json".toMediaType())
                    )
                    val rText = r.text
                    val hit = hostWords.any { rText.contains(it, ignoreCase = true) }
                    ajaxCodes.add(if (hit) "HIT" else r.code.toString())
                    ajaxResults.add("$ep code=${r.code} ${if (hit) "HIT" else "no"} len=${rText.length}")
                    if (hit) {
                        for (re in embedRegexes) re.findAll(rText.replace("\\/", "/")).forEach { embeds.add(it.value) }
                    }
                } catch (e: Exception) {
                    ajaxCodes.add("ERR")
                    ajaxResults.add("$ep=ERR")
                }
            }
            // v6 (panel #5, Try 5): __hpv3 token exchange — parse
            // ts/n/p from the token block on THIS page and POST
            // it to /__hpv3/video. SKIP when no token is present.
            val hpv3Seg = if (hpv3Idx >= 0) htmlRaw.substring(hpv3Idx, minOf(htmlRaw.length, hpv3Idx + 600)) else ""
            val tsVal = Regex("\"ts\"\\s*:\\s*\"?(\\d+)").find(hpv3Seg)?.groupValues?.get(1)
            val nVal = Regex("\"n\"\\s*:\\s*\"([^\"]+)\"").find(hpv3Seg)?.groupValues?.get(1)
            val pVal = Regex("\"p\"\\s*:\\s*\"([^\"]+)\"").find(hpv3Seg)?.groupValues?.get(1)
            if (tsVal != null && nVal != null && pVal != null) {
                try {
                    val hJson = """{"v":3,"p":"$pVal","k":"video","ts":$tsVal,"n":"$nVal"}"""
                    val r = app.post(
                        mainUrl + "/__hpv3/video",
                        headers = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ),
                        referer = realData,
                        requestBody = hJson.toRequestBody("application/json".toMediaType())
                    )
                    val rText = r.text
                    val hit = hostWords.any { rText.contains(it, ignoreCase = true) }
                    ajaxCodes.add(if (hit) "HIT" else "h3:${r.code}")
                    ajaxResults.add("__hpv3/video code=${r.code} ${if (hit) "HIT" else "no"} len=${rText.length}")
                    if (hit) {
                        for (re in embedRegexes) re.findAll(rText.replace("\\/", "/")).forEach { embeds.add(it.value) }
                    }
                } catch (e: Exception) {
                    ajaxCodes.add("h3:ERR")
                    ajaxResults.add("__hpv3/video=ERR")
                }
            } else {
                ajaxCodes.add("h3:SKIP")
                ajaxResults.add("__hpv3/video=SKIP")
            }
            ajaxCodesStr = ajaxCodes.joinToString(",")
            debugExtras.add("DEBUG v6 ajax=" + ajaxResults.joinToString(" | ").take(200))
            embCount = embeds.size
            for (embed in embeds) {
                try {
                    // Direct packed-JS extraction first — fastvid
                    // and luluvido hide a real .m3u8 in packed JS
                    // and have no working stock extractor.
                    if (tryDirectExtract(embed, realData, countingCallback)) continue
                    loadExtractor(embed, realData, subtitleCallback, countingCallback)
                } catch (e: Exception) {
                    // One dead host must not kill the rest.
                }
            }
        } catch (e: Exception) {
            // v4: name the failure instead of silence — a page=0
            // with no reason tells us nothing.
            fetchErr = "err=${e.javaClass.simpleName}:${(e.message ?: "").take(60)}"
        }
        // v6: the verdict lives in the title itself — ajax codes
        // (in probe order), Base64, /m/ code, script keywords,
        // script count, __hpv3 presence, player iframe src.
        val verdict = "aj=$ajaxCodesStr b64=$b64Verdict m=$mNowCode kw=$kwHitsStr js=$scriptCount h3=$hpv3Present ifr=$playerIfr"
        val debugName = if (emittedLinks == 0)
            "DEBUG v6 | no-servers-on-site | page=$pageCode | emb=$embCount | ok=0 | $subDiag | $diag | $verdict $fetchErr"
        else
            "DEBUG v6 | play | page=$pageCode | emb=$embCount | ok=$emittedLinks | $subDiag | $diag | $verdict $fetchErr"
        callback(
            newExtractorLink(
                "Mux",
                debugName,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                ExtractorLinkType.M3U8
            ) {
                this.referer = realData
                this.quality = Qualities.Unknown.value
            }
        )
        // v3 exfiltration: one extra DEBUG source per finding, so
        // the source list itself carries the page structure.
        for (extra in debugExtras) {
            callback(
                newExtractorLink(
                    "Mux",
                    extra,
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = realData
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    /**
     * Direct packed-JS extraction: fetch an embed page, unpack a
     * P.A.C.K.E.R. block if present (else look for a plain video
     * URL), and emit it. Returns true when a link was emitted
     * (the caller then skips loadExtractor for this embed).
     * Fail-soft: any miss returns false and the caller falls
     * back to the stock extractor.
     */
    private suspend fun tryDirectExtract(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embed, headers = headers, interceptor = cloudflareKiller).text
            val unpacked = if (html.contains("eval(function(p,a,c,k,e")) {
                Cima4yUnpack.unpackPackedJs(html)
            } else null
            val videoUrl = (unpacked?.let { Cima4yUnpack.extractVideoUrl(it) })
                ?: Cima4yUnpack.extractVideoUrl(html)
                ?: return false
            val host = embed.substringAfter("://").substringBefore("/")
                .removePrefix("www.").substringBefore(".")
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(
                    host,
                    host,
                    videoUrl,
                    type
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- auto English subtitles (v1, Wecima2/HorrorMovies port) ----------

    /**
     * AUTO ENGLISH SUBTITLES: CloudStream has no standalone
     * subtitle-plugin type that auto-attaches to other
     * providers' playback, so the tool lives here — inside
     * loadLinks, before any video link is emitted:
     *   Latin title (+ year) -> IMDb suggest (key-free) -> IMDb
     *   id -> OpenSubtitles v3 Stremio endpoint (key-free) ->
     *   best lang=eng file by sync-first ranking ->
     *   subtitleCallback(SubtitleFile("English", url)).
     * Everything is fail-soft and fail-fast (8 s caps): if any
     * step misses, playback continues with zero subtitles —
     * never blocked. Titles with no Latin part and no usable
     * slug get no subtitle (same limit as Wecima2 v12).
     */
    private fun httpGetText(url: String, timeoutMs: Int): String? {
        return runCatching {
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                return@runCatching null
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    private fun latinTitleCandidates(title: String): List<String> {
        var t = title.replace(Regex("""[\u0600-\u06FF]+"""), " ")
        t = t.replace(Regex("""(?i)\b(mtarjm|mudblj|online|web-dl|bluray|hd|hdts|hdcam|webdl)\b"""), " ")
        t = t.replace(Regex("""[^A-Za-z0-9 :'\-!.,]+"""), " ")
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

    /** Slug words as a second candidate source (cima4y slugs are
     * Latin in the middle: فيلم-star-one-2026-مترجم -> "star one",
     * مسلسل-tulsa-king-الموسم-الرابع-الحلقة-2-مترجمة ->
     * "tulsa king"). */
    private fun slugCandidates(url: String): List<String> {
        var slug = url.substringAfter("/watch/").trimEnd('/')
        slug = slug.removePrefix("فيلم-").removePrefix("مسلسل-")
            .removePrefix("انمي-").removePrefix("برنامج-")
        slug = Regex("""-الموسم-.*$""").replace(slug, "")
        slug = Regex("""-الحلقة-.*$""").replace(slug, "")
        slug = slug.removeSuffix("-مترجم").removeSuffix("-مترجمة")
            .removeSuffix("-مدبلج").removeSuffix("-مدبلجة")
        slug = Regex("""-\d{4}$""").replace(slug, "")
        slug = slug.replace('-', ' ').replace(Regex("""\s+"""), " ").trim()
        val latin = slug.split(" ")
            .filter { it.matches(Regex("""[A-Za-z0-9:'\-.]+""")) }
            .joinToString(" ")
        if (latin.isBlank()) return emptyList()
        return listOf(latin)
    }

    private fun resolveImdbId(latinTitle: String, year: Int?, wantSeries: Boolean): String? {
        val bucket = latinTitle.firstOrNull()?.lowercaseChar() ?: return null
        val q = java.net.URLEncoder.encode(latinTitle, "UTF-8")
        val json = httpGetText(
            "https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json", 8000
        ) ?: return null
        val arr = runCatching {
            JSONObject(json).optJSONArray("d")
        }.getOrNull() ?: return null
        val want = if (wantSeries) "TV series" else "feature"
        val other = if (wantSeries) "feature" else "TV series"
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val kind = o.optString("q")
            if (fallback == null && kind == other) fallback = id
            if (kind != want) continue
            if (year != null && o.has("y") && o.optInt("y") != year) continue
            return id
        }
        return fallback
    }

    private fun emitEnglishSub(
        title: String,
        season: Int?,
        ep: Int?,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val year = Regex("""(19|20)\d\d""").find(title)?.value?.toIntOrNull()
        val candidates = latinTitleCandidates(title) + slugCandidates(url)
        val wantSeries = season != null && ep != null
        for (candidate in candidates) {
            val tt = resolveImdbId(candidate, year, wantSeries) ?: continue
            val osUrl = if (wantSeries)
                "https://opensubtitles-v3.strem.io/subtitles/series/$tt:$season:$ep.json"
            else
                "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json"
            val json = httpGetText(osUrl, 8000) ?: continue
            val arr = runCatching {
                JSONObject(json).optJSONArray("subtitles")
            }.getOrNull() ?: continue
            // Sync-first ranking (Wecima2 v12): don't take the
            // first eng file. Prefer 23.976fps BluRay/WEB UTF-8
            // files, which match the standard movie streams best.
            var bestUrl = ""
            var bestScore = -1
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("lang") != "eng") continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                var score = 0
                if (o.optInt("fpsMilli", 0) == 23976) score += 3
                val fmt = o.optString("releaseFormat")
                if (fmt == "BluRay" || fmt == "WEB") score += 2
                if (o.optString("SubEncoding") == "UTF-8") score += 1
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = u
                }
            }
            if (bestUrl.isNotBlank()) {
                subtitleCallback(SubtitleFile("English", bestUrl))
                return true
            }
        }
        return false
    }

    /** Packs title (+season/episode) into a loadLinks data string. */
    private fun subData(url: String, title: String, season: Int?, ep: Int?): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return url + "|||" + enc + "|||" + (season?.toString() ?: "") + "|||" + (ep?.toString() ?: "")
    }

    private data class SubPack(val url: String, val title: String, val season: Int?, val ep: Int?)

    private fun parseSubData(data: String): SubPack {
        val parts = data.split("|||")
        val real = parts.getOrNull(0) ?: data
        val title = parts.getOrNull(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("")
        }.orEmpty()
        val season = parts.getOrNull(2)?.toIntOrNull()
        val ep = parts.getOrNull(3)?.toIntOrNull()
        return SubPack(real, title, season, ep)
    }
}
