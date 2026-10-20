package com.turkdub

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
 * TurkDub (تركي مدبلج) — Turkish series DUBBED in Arabic, aggregated
 * from the three strongest Arabic sources measured live 2026-10-20:
 *
 * FOSTA (fosta-tv.work) — PHP Melody site with a dedicated
 * «مسلسلات تركي مدبلجة» category: its nav submenu lists ~18 dubbed
 * Turkish series as their own categories (الحفرة = 427 episodes …),
 * and the category itself is an episode river (500+ pages).
 * watch.php pages carry a play.php?vid= endpoint whose HTML lists
 * 4–10 server embeds per episode (Voe, MixDrop, VidMoly, Byse,
 * StreamHide family, mp4upload, ok.ru + 1vid.xyz …).
 *
 * QESSET-DUB (k.3chk.net) — the Qesset operator's dubbed sister
 * site: /genre/serie-mudblij-q104/ series index (17 pages), series
 * pages link /video/ep/{stem}-season-{s}-episode-{e}/ pages whose
 * shortlink id opens player slots /embed/{n}/{pid}/2/ (referer
 * gated) — exactly 2 filled slots (miravd, mwdy), both Cloudflare
 * walled from servers, so they are dug ON THE PHONE with the
 * /embed/ page as referer (the browser's frame chain).
 *
 * BRSTEJ (brsteej.asia) — same Melody family (cat03.php): its
 * Turkish category river carries mostly dubbed episodes (title
 * filter مدبلج); play.php embeds include hdup400 (the known Brestj
 * chain) + ok.ru.
 *
 * Episode cards in the rivers open their whole SERIES: for Fosta
 * the series' own category is matched from the watch page's nav;
 * otherwise the series is built from the site search filtered to
 * the exact series name.
 */
class TurkDubProvider : MainAPI() {
    override var mainUrl = "https://fosta-tv.work"
    override var name = "تركي مدبلج"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val fosta = "https://fosta-tv.work"
    private val brst = "https://brsteej.asia"
    private val chk = "https://k.3chk.net"
    private val relayBase = "https://egydead-stremio.onrender.com"

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "fosta-river" to "فوستا — أحدث الحلقات المدبلجة",
        "fosta-series" to "فوستا — مسلسلات تركية مدبلجة",
        "chk-series" to "قصة عشق — مسلسلات مدبلجة",
        "brst-river" to "برستيج — أحدث الحلقات المدبلجة"
    )

    // ------------------------------------------------------------------
    // Shared title helpers (Melody episode titles)
    // ------------------------------------------------------------------

    private val epNumRegex = Regex("""الحلقة\s*(\d+)""")

    /** "مسلسل الحفرة الحلقة 427 مدبلجة" → ("الحفرة", 427). */
    private fun parseEpTitle(raw: String): Pair<String, Int?> {
        val t = raw.replace(Regex("""\s+"""), " ").trim()
        val ep = epNumRegex.find(t)?.groupValues?.get(1)?.toIntOrNull()
        var s = t
        s = s.replace(Regex("""^مسلسل\s+"""), "")
        val cut = epNumRegex.find(s)?.range?.first
        if (cut != null) s = s.substring(0, cut)
        s = s.replace(Regex("""\b(مدبلج|مدبلجة|مدبلج\s*HD|HD)\b"""), "")
            .replace(Regex("""\s+"""), " ").trim().trim('-', '—', ' ').trim()
        return s to ep
    }

    private fun normName(s: String): String = s
        .replace(Regex("""\b(مسلسل|مدبلج|مدبلجة|HD)\b"""), "")
        .replace(Regex("""\s+"""), " ").trim().lowercase()

    // ------------------------------------------------------------------
    // Melody cards (Fosta + Brstej share the CMS)
    // ------------------------------------------------------------------

    private data class MelodyCard(
        val title: String,
        val watchUrl: String,
        val poster: String?
    )

    private fun melodyCards(doc: Document): List<MelodyCard> {
        val out = LinkedHashMap<String, MelodyCard>()
        for (a in doc.select("a[href*=watch.php]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.contains("watch.php?vid=")) continue
            val vid = Regex("""vid=([a-z0-9]+)""").find(href)?.groupValues?.get(1)
                ?: continue
            if (out.containsKey(vid)) continue
            var title = a.attr("title")
            if (title.isBlank()) {
                title = a.selectFirst("img")?.attr("alt") ?: ""
            }
            if (title.isBlank()) title = a.text()
            title = title.replace(Regex("""\s+"""), " ").trim()
            if (title.isBlank()) continue
            val li: Element? = a.parents().firstOrNull { it.tagName() == "li" } ?: a.parent()
            var poster: String? = null
            val img = (li ?: a).selectFirst("img")
            if (img != null) {
                val echo = img.attr("data-echo")
                val src = img.attr("abs:src").ifBlank { img.attr("src") }
                poster = when {
                    echo.isNotBlank() -> echo
                    src.isNotBlank() && !src.contains("echo-lzld") &&
                        !src.contains("placeholder") -> src
                    else -> null
                }
            }
            out[vid] = MelodyCard(title, href, poster)
        }
        return out.values.toList()
    }

    private fun melodySeriesResponse(card: MelodyCard): SearchResponse {
        val (seriesName, _) = parseEpTitle(card.title)
        return newTvSeriesSearchResponse(
            seriesName.ifBlank { card.title },
            card.watchUrl,
            TvType.TvSeries
        ) {
            this.posterUrl = card.poster
        }
    }

    // ------------------------------------------------------------------
    // 3chk cards (ported from Qesset v2)
    // ------------------------------------------------------------------

    private fun parseChkCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (block in doc.select("div.EpisodeBlock")) {
            val a = block.selectFirst("a[href]") ?: continue
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val isSeries = href.contains("/video/tvshows/")
            val isMovie = href.contains("/video/movies/")
            if (!isSeries && !isMovie) continue
            val title = block.selectFirst(".EpisodeBlockTitle")?.text()
                ?.replace(Regex("""\s+"""), " ")?.trim()
                ?.ifBlank { null } ?: continue
            val img = block.selectFirst("img")
            val poster = img?.attr("data-img")?.ifBlank { null }
                ?: img?.attr("abs:src")?.ifBlank { null }
            out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return out
    }

    /** Fosta's dubbed-series index: the submenu under the parent category link. */
    private fun fostaSeriesIndex(doc: Document): List<SearchResponse> {
        val parent = doc.select("a[href*=moslslat-turkia-modblga]").firstOrNull()
            ?: return emptyList()
        val li = parent.parents().firstOrNull { it.tagName() == "li" } ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        for (a in li.select("ul.dropdown-menu a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.contains("category.php?cat=")) continue
            if (href.contains("moslslat-turkia-modblga")) continue
            val t = a.text().replace(Regex("""\s+"""), " ").trim()
            if (t.isBlank()) continue
            out += newTvSeriesSearchResponse(t, href, TvType.TvSeries) {}
        }
        return out
    }

    // ------------------------------------------------------------------
    // Home sections
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when (request.data) {
            "fosta-river" -> {
                val url = "$fosta/category.php?cat=moslslat-turkia-modblga&page=$page"
                val doc = app.get(url, headers = browserHeaders).document
                val cards = melodyCards(doc)
                val hasNext = cards.size >= 12 &&
                    doc.select("a[href*=page=${page + 1}]").isNotEmpty()
                newHomePageResponse(request.name, cards.map { melodySeriesResponse(it) }, hasNext)
            }
            "fosta-series" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                val doc = app.get(
                    "$fosta/category.php?cat=moslslat-turkia-modblga",
                    headers = browserHeaders
                ).document
                newHomePageResponse(request.name, fostaSeriesIndex(doc), false)
            }
            "chk-series" -> {
                val url = if (page <= 1) {
                    "$chk/genre/serie-mudblij-q104/"
                } else {
                    "$chk/genre/serie-mudblij-q104/page/$page/"
                }
                val doc = app.get(url, headers = browserHeaders).document
                val hasNext = doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
                newHomePageResponse(request.name, parseChkCards(doc), hasNext)
            }
            else -> { // brst-river
                val url = "$brst/cat03.php?cat=ty9-2025&page=$page"
                val doc = app.get(url, headers = browserHeaders).document
                val raw = melodyCards(doc)
                val dubbed = raw.filter { it.title.contains("مدبلج") }
                val hasNext = raw.size >= 10 &&
                    doc.select("a[href*=page=${page + 1}]").isNotEmpty()
                newHomePageResponse(request.name, dubbed.map { melodySeriesResponse(it) }, hasNext)
            }
        }
    }

    // ------------------------------------------------------------------
    // Search — Fosta + Brstej (episode results grouped into series).
    // 3chk's WP search 302s (AJAX-only), so it is browse-only in v1.
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (base in listOf(fosta, brst)) {
            val url = "$base/search.php?keywords=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val doc = runCatching {
                app.get(url, headers = browserHeaders).document
            }.getOrNull() ?: continue
            for (card in melodyCards(doc)) {
                val resp = melodySeriesResponse(card)
                out.putIfAbsent(normName(resp.name) + "@" + base, resp)
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------
    // Parallel page fetching for episode builds
    // ------------------------------------------------------------------

    private fun fetchPages(urls: List<String>): List<Document?> {
        if (urls.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(urls.size, 5).coerceAtLeast(1))
        try {
            val futures = urls.map { u ->
                pool.submit(Callable { fetchDoc(u) })
            }
            return futures.map {
                runCatching { it.get(25, TimeUnit.SECONDS) }.getOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------
    // Melody series building
    // ------------------------------------------------------------------

    private fun episodesFromCards(
        cards: List<MelodyCard>,
        fallbackPoster: String?
    ): List<Episode> {
        val byNum = LinkedHashMap<Int, MelodyCard>()
        val noNum = ArrayList<MelodyCard>()
        for (c in cards) {
            val (_, ep) = parseEpTitle(c.title)
            if (ep != null) byNum.putIfAbsent(ep, c) else noNum += c
        }
        val sorted = byNum.entries.sortedBy { it.key }
        val out = ArrayList<Episode>()
        for ((num, c) in sorted) {
            out += newEpisode(c.watchUrl) {
                this.name = "الحلقة $num"
                this.episode = num
                this.season = 1
                this.posterUrl = c.poster ?: fallbackPoster
            }
        }
        for (c in noNum) {
            out += newEpisode(c.watchUrl) {
                this.name = c.title
                this.season = 1
                this.posterUrl = c.poster ?: fallbackPoster
            }
        }
        return out
    }

    /** All episode cards of a Melody category (paginated, capped). */
    private fun melodyCategoryCards(firstUrl: String): List<MelodyCard> {
        val first = fetchDoc(firstUrl) ?: return emptyList()
        val all = ArrayList<MelodyCard>()
        all += melodyCards(first)
        val maxPage = Regex("""page=(\d+)""").findAll(first.html())
            .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
        val last = minOf(maxPage, 15)
        if (last > 1) {
            val docs = fetchPages((2..last).map { p ->
                firstUrl.replace(Regex("""([?&])page=\d+"""), "$1page=$p")
                    .let { if (it.contains("page=")) it else "$it&page=$p" }
            })
            for (d in docs) {
                if (d == null) continue
                val cards = melodyCards(d)
                if (cards.isEmpty()) break
                all += cards
            }
        }
        val seen = HashSet<String>()
        return all.filter { seen.add(it.watchUrl) }
    }

    private suspend fun loadMelodyCategory(url: String): LoadResponse {
        val cards = melodyCategoryCards(url)
        if (cards.isEmpty()) throw ErrorLoadingException("no episodes")
        val (seriesName, _) = parseEpTitle(cards.first().title)
        val poster = cards.firstOrNull { it.poster != null }?.poster
        val eps = episodesFromCards(cards, poster)
        return newTvSeriesLoadResponse(
            seriesName.ifBlank { "مسلسل مدبلج" },
            url,
            TvType.TvSeries,
            eps
        ) {
            this.posterUrl = poster
        }
    }

    /**
     * An episode (river/search card) opens its whole series: Fosta's
     * watch page nav gives the exact series category when the name
     * matches; otherwise build from the site search filtered to the
     * exact series name.
     */
    private suspend fun loadMelodyEpisode(watchUrl: String): LoadResponse {
        val isFosta = watchUrl.contains("fosta")
        val base = if (isFosta) fosta else brst
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull() ?: throw ErrorLoadingException("watch page failed")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { null } ?: doc.title()
        val (seriesName, _) = parseEpTitle(ogTitle)
        if (seriesName.isBlank()) throw ErrorLoadingException("no series name")

        if (isFosta) {
            val idx = fostaSeriesIndex(doc)
            val match = idx.firstOrNull {
                normName(it.name) == normName(seriesName) ||
                    normName(it.name).contains(normName(seriesName))
            }
            if (match != null) return loadMelodyCategory(match.url)
        }

        val enc = java.net.URLEncoder.encode(seriesName, "UTF-8")
        val pageUrls = (1..6).map { "$base/search.php?keywords=$enc&page=$it" }
        val docs = fetchPages(pageUrls)
        val cards = ArrayList<MelodyCard>()
        for (d in docs) {
            if (d == null) continue
            for (c in melodyCards(d)) {
                val (sn, _) = parseEpTitle(c.title)
                if (normName(sn) == normName(seriesName)) cards += c
            }
        }
        // The tapped episode itself is guaranteed present.
        val tapped = melodyCards(doc)
        for (c in tapped) {
            if (c.watchUrl == watchUrl) cards += c
        }
        val seen = HashSet<String>()
        val uniq = cards.filter { seen.add(it.watchUrl) }
        if (uniq.isEmpty()) throw ErrorLoadingException("series build empty")
        val poster = uniq.firstOrNull { it.poster != null }?.poster
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val eps = episodesFromCards(uniq, poster)
        return newTvSeriesLoadResponse(seriesName, watchUrl, TvType.TvSeries, eps) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------------
    // 3chk load (ported from Qesset v2)
    // ------------------------------------------------------------------

    private fun og(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=og:$prop]")?.attr("content")?.ifBlank { null }

    private val chkEpRegex = Regex("""-season-(\d+)-episode-(\d+)""")

    private fun chkStemOf(epUrl: String): String {
        val slug = epUrl.trimEnd('/').substringAfterLast('/')
        return chkEpRegex.replace(slug, "")
    }

    private fun chkEpisodeLinks(doc: Document): List<String> {
        val all = doc.select("a[href*=/video/ep/]").map {
            it.attr("abs:href").ifBlank { it.attr("href") }
        }.filter { chkEpRegex.containsMatchIn(it) }.distinct()
        if (all.isEmpty()) return emptyList()
        val byStem = all.groupBy { chkStemOf(it) }
        val dominant = byStem.maxByOrNull { it.value.size }?.value ?: return emptyList()
        return dominant.sortedBy { u ->
            val m = chkEpRegex.find(u)
            val s = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val e = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            s * 10000 + e
        }
    }

    private fun buildChkEpisodes(doc: Document): List<Episode> {
        val poster = og(doc, "image")
        return chkEpisodeLinks(doc).map { link ->
            val m = chkEpRegex.find(link)
            val s = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = m?.groupValues?.get(2)?.toIntOrNull()
            newEpisode(link) {
                this.name = if (e != null) "الحلقة $e" else "حلقة"
                this.season = s
                this.episode = e
                this.posterUrl = poster
            }
        }
    }

    private fun cleanChkTitle(t: String?): String {
        var s = (t ?: "").replace(Regex("""\s+"""), " ").trim()
        s = s.replace(Regex("""موقع قصة عشق.*$"""), "")
            .replace(Regex("""-\s*قصة عشق.*$"""), "").trim()
        return s
    }

    private suspend fun loadChk(url: String): LoadResponse {
        if (url.contains("/video/movies/")) {
            val doc = app.get(url, headers = browserHeaders).document
            return newTvSeriesLoadResponse(
                cleanChkTitle(og(doc, "title")).ifBlank { "فيلم مدبلج" },
                url,
                TvType.TvSeries,
                listOf(
                    newEpisode(url) {
                        this.name = "مشاهدة"
                        this.posterUrl = og(doc, "image")
                    }
                )
            ) {
                this.posterUrl = og(doc, "image")
                this.plot = og(doc, "description")
            }
        }
        if (url.contains("/video/ep/")) {
            val doc = app.get(url, headers = browserHeaders).document
            val showName = cleanChkTitle(og(doc, "title"))
                .replace(Regex("""الحلقة.*$"""), "").trim()
            if (showName.isNotBlank()) {
                val searchDoc = runCatching {
                    app.get("$chk/genre/serie-mudblij-q104/", headers = browserHeaders).document
                }.getOrNull()
                if (searchDoc != null) {
                    val hit = parseChkCards(searchDoc).firstOrNull {
                        normName(it.name) == normName(showName) ||
                            normName(it.name).contains(normName(showName))
                    }
                    if (hit != null) return loadChkSeries(hit.url)
                }
            }
            val eps = buildChkEpisodes(doc)
            if (eps.isEmpty()) throw ErrorLoadingException("no episodes")
            return newTvSeriesLoadResponse(
                showName.ifBlank { "مسلسل مدبلج" },
                url,
                TvType.TvSeries,
                eps
            ) {
                this.posterUrl = og(doc, "image")
                this.plot = og(doc, "description")
            }
        }
        return loadChkSeries(url)
    }

    private suspend fun loadChkSeries(showUrl: String): LoadResponse {
        val doc = app.get(showUrl, headers = browserHeaders).document
        val eps = buildChkEpisodes(doc)
        if (eps.isEmpty()) throw ErrorLoadingException("no episodes")
        return newTvSeriesLoadResponse(
            cleanChkTitle(og(doc, "title")).ifBlank { "مسلسل مدبلج" },
            showUrl,
            TvType.TvSeries,
            eps
        ) {
            this.posterUrl = og(doc, "image")
            this.plot = og(doc, "description")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("3chk.net") -> loadChk(url)
            url.contains("category.php?cat=") || url.contains("cat03.php?cat=") ->
                loadMelodyCategory(url)
            url.contains("watch.php") -> loadMelodyEpisode(url)
            else -> throw ErrorLoadingException("unknown url")
        }
    }

    // ------------------------------------------------------------------
    // Playback helpers (ported from the proven providers)
    // ------------------------------------------------------------------

    private fun fetchDoc(url: String, referer: String? = null): Document? = runCatching {
        kotlinx.coroutines.runBlocking {
            app.get(url, headers = pageHeaders(null, referer)).document
        }
    }.getOrNull()

    private fun fetchText(url: String, referer: String? = null): String? = runCatching {
        kotlinx.coroutines.runBlocking {
            app.get(url, headers = pageHeaders(null, referer)).text
        }
    }.getOrNull()

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
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean).forEach { add(it.value) }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean).forEach { add(it.value) }
        Regex("""["'](//[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)["']""").findAll(clean).forEach {
            add("https:" + it.groupValues[1])
        }
        return out.take(4)
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
            out += Regex("""\b\w+\b""").replace(payload) { mr -> map[mr.value] ?: mr.value }
        }
        return out
    }

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

    private data class DugCandidate(
        val label: String,
        val url: String,
        val referer: String?
    )

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

    private fun digAllForDirects(url: String, referer: String?): List<String> {
        val doc = fetchText(url, referer) ?: return emptyList()
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
                val inner = fetchText(abs, url)
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
        callback: (ExtractorLink) -> Unit
    ) {
        val isHls = directUrl.contains(".m3u8")
        val headers = LinkedHashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = asciiSafe(referer)
        headers["User-Agent"] = browserUA
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
                emitDirect(label, link, embed, callback)
                emitted++
            }
        }
        return emitted
    }

    // ------------------------------------------------------------------
    // Melody playback: watch URL → play.php → embeds → extractors,
    // then phone digs, then relay.
    // ------------------------------------------------------------------

    private val extractorHosts = listOf(
        "voe.", "mixdrop", "vidmoly", "byse", "hgcloud", "listeamed",
        "streamhide", "mp4upload", "ok.ru", "dood", "filemoon", "vidoza",
        "streamtape", "upstream", "vidhide", "earnvids", "uqload",
        "dailymotion", "youtube", "streamwish", "filelions"
    )

    private fun collectEmbeds(playHtml: String, selfHosts: Set<String>): List<String> {
        val clean = unescapeSlashes(playHtml)
        val out = LinkedHashSet<String>()
        for (m in Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""").findAll(clean)) {
            val u = m.groupValues[1]
            if (u.startsWith("http")) out += u
        }
        for (m in Regex("""https?://[a-z0-9.-]+/(?:embed|e|videoembed|v)/[^"'\s<>]+""")
            .findAll(clean)) {
            out += m.value
        }
        for (m in Regex("""https?://[a-z0-9.-]+/embed-[a-z0-9]+\.html""").findAll(clean)) {
            out += m.value
        }
        return out.filter { u ->
            val h = hostOf(u)
            h.isNotBlank() && selfHosts.none { h.contains(it) } &&
                !u.contains("embed.php")
        }
    }

    private suspend fun loadLinksMelody(
        data: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt
        var emitted = 0
        val diag = ArrayList<String>()
        val isFosta = data.contains("fosta")
        val base = if (isFosta) fosta else brst
        diag += if (isFosta) "TurkDub·Fosta" else "TurkDub·Brst"
        val vid = Regex("""vid=([a-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            emitDebug(diag + "vid=NONE", callback)
            return
        }
        val playHtml = fetchText("$base/play.php?vid=$vid", data)
        if (playHtml == null) {
            emitDebug(diag + "play=ERR", callback)
            return
        }
        val embeds = collectEmbeds(playHtml, setOf("fosta-tv.work", "brsteej.asia", "brstej.com"))
        diag += "srv=${embeds.size}"

        val (extEmbeds, digEmbeds) = embeds.partition { e ->
            extractorHosts.any { hostOf(e).contains(it) }
        }

        // Phase A — app extractors on the known hosts.
        var extOk = 0
        for (embed in extEmbeds) {
            if (emitted >= 6 || elapsed() > 12000) break
            val before = emitted
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { link ->
                    emitted++
                    callback(link)
                }
            }
            if (emitted > before) extOk++
        }
        diag += "ext=$extOk"

        // Phase B — phone digs for the rest.
        val dug = ArrayList<DugCandidate>()
        for (embed in digEmbeds) {
            if (elapsed() > 16000 || dug.size >= 6) break
            val links = runCatching { digAllForDirects(embed, data) }
                .getOrDefault(emptyList())
            for (link in links) {
                dug += DugCandidate(hostOf(embed), link, embed)
            }
        }
        var digOk = 0
        for (cand in probeCandidates(dug)) {
            if (emitted >= 6) break
            emitDirect(cand.label, cand.url, cand.referer, callback)
            emitted++
            digOk++
        }
        diag += "dig=$digOk"

        // Phase C — relay for anything left, only if nothing played.
        var relayOk = 0
        if (emitted == 0) {
            for (embed in embeds) {
                if (relayOk >= 2 || elapsed() > 24000) break
                val n = runCatching {
                    resolveViaRelay(embed, data, hostOf(embed), callback)
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
    }

    // ------------------------------------------------------------------
    // 3chk playback (ported from Qesset v2): pid → /embed/ slots →
    // phone dig per host with the frame referer; DEBUG outcome codes.
    // ------------------------------------------------------------------

    private fun digChkHost(
        embedUrl: String,
        embedPageUrl: String
    ): Pair<List<String>, String> {
        val body = fetchText(embedUrl, embedPageUrl)
            ?: return emptyList<String>() to "err"
        if (body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com")
        ) return emptyList<String>() to "cf"
        if (body.contains("Embeds disabled", ignoreCase = true)) {
            return emptyList<String>() to "disabled"
        }
        val out = LinkedHashSet<String>()
        out += extractAllDirects(body)
        if (out.isEmpty()) {
            for (unpacked in unpackPackedJs(body)) {
                out += extractAllDirects(unpacked)
                if (out.isNotEmpty()) break
            }
        }
        if (out.isEmpty()) {
            val src = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                .find(body)?.groupValues?.get(1)
            if (!src.isNullOrBlank() && !src.startsWith("about:") &&
                (src.startsWith("http") || src.startsWith("//"))
            ) {
                val abs = if (src.startsWith("//")) "https:$src" else src
                val inner = fetchText(abs, embedUrl)
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
        return out.take(3) to if (out.isEmpty()) "nodirect" else "ok${out.size}"
    }

    private suspend fun loadLinksChk(
        data: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt
        val diag = ArrayList<String>()
        diag += "TurkDub·Chk"
        var emitted = 0

        val html = fetchText(data) ?: run {
            emitDebug(diag + "page=ERR", callback)
            return
        }
        val pid = Regex("""rel=["']shortlink["'] href=["'][^"']*\?p=(\d+)""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""\?p=(\d+)""").find(html)?.groupValues?.get(1)
            ?: run {
                emitDebug(diag + "pid=NONE", callback)
                return
            }
        diag += "pid=$pid"

        val typeFlag = if (data.contains("/video/movies/")) "1" else "2"
        val slots = ArrayList<Pair<String, String>>()
        var emptyStreak = 0
        for (n in 1..6) {
            if (elapsed() > 10000) break
            val embedPage = "$chk/embed/$n/$pid/$typeFlag/"
            val pageHtml = fetchText(embedPage, data)
            val src = pageHtml?.let {
                Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                    .find(it)?.groupValues?.get(1)
            }?.ifBlank { null }
            if (src == null) {
                emptyStreak++
                if (n >= 2 && emptyStreak >= 2) break
            } else {
                emptyStreak = 0
                slots += embedPage to src
            }
        }
        diag += "srv=${slots.size}"

        val dug = ArrayList<DugCandidate>()
        val digCodes = ArrayList<String>()
        for ((embedPage, hostEmbed) in slots) {
            if (elapsed() > 16000) break
            val (links, code) = runCatching { digChkHost(hostEmbed, embedPage) }
                .getOrDefault(emptyList<String>() to "err")
            digCodes += "${hostOf(hostEmbed)}:$code"
            for (link in links) {
                dug += DugCandidate("مدبلج — ${hostOf(hostEmbed)}", link, hostEmbed)
            }
        }
        diag += "dig=[${digCodes.joinToString(",")}]"
        for (cand in probeCandidates(dug)) {
            if (emitted >= 3) break
            emitDirect(cand.label, cand.url, cand.referer, callback)
            emitted++
        }
        diag += "t=${elapsed() / 1000}s"
        if (emitted == 0) emitDebug(diag, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        when {
            data.contains("3chk.net") -> loadLinksChk(data, callback)
            data.contains("watch.php") -> loadLinksMelody(data, callback, subtitleCallback)
            else -> emitDebug(listOf("TurkDub", "data=UNKNOWN"), callback)
        }
        return true
    }
}
