package com.movizland

import com.lagradost.cloudstream3.Episode
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * MovizLand (موفيز لاند, moviz.land) — big Arabic WordPress portal,
 * traced live 2026-10-23:
 *
 * - Content = WP posts, ONE per movie / per episode. Titles:
 *   «مشاهدة فيلم X» / «مشاهدة مسلسل X الحلقة N» (multi-season:
 *   «… الموسم الثاني الحلقة N»). wp-json is OPEN (posts/tags/
 *   categories) but posts have NO featured image — posters live in
 *   the theme's card data-image and in each post page's og:image.
 * - Listings: hubs /movies/ and /series/ carry named sections of
 *   .movie-slide cards; category archives /category/{slug}/page/{n}/
 *   carry .archive-card x30; site search /?s= carries .search-card.
 *   All card types expose data-title / data-url / data-image.
 * - Series grouping: every episode post carries a tag named
 *   «تحميل مسلسل {name}» — posts?tags={id} is exactly that series'
 *   episode list (sort by parsed season/episode; WP date order is
 *   scrambled). Fallback: wp-json search filtered by parsed name.
 * - Playback: watch page {post}/watch/ lists .server-card buttons
 *   (data-src embeds). Census: regular titles 8–9 servers — voe.sx,
 *   vidmoly.org, bysesukior.com, hgcloud.to, uqload.vc, ok.ru,
 *   vk.ru / vkvideo.ru (extractors) + 1vid.xyz, vidspeed.org (dig
 *   class). New/exclusive titles carry ONE card: upbom.cam
 *   («سيرفر موفيز لاند») whose embed page lists DIRECT mp4s
 *   (360/720/1080) — dug on the phone like the other dig hosts.
 */
class MovizLandProvider : MainAPI() {
    override var mainUrl = "https://www.moviz.land"
    override var name = "موفيز لاند"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val uaHeaders = mapOf("User-Agent" to browserUA)

    override val mainPage = mainPageOf(
        "hub:movies" to "أفلام",
        "hub:series" to "مسلسلات",
        "cat:https://www.moviz.land/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d9%87/" to
            "أفلام أجنبية مترجمة",
        "cat:https://www.moviz.land/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to
            "أفلام عربي",
        "cat:https://www.moviz.land/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a%d8%a9/" to
            "مسلسلات عربية",
        "cat:https://www.moviz.land/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/" to
            "مسلسلات تركية مدبلجة",
        "cat:https://www.moviz.land/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/" to
            "مسلسلات تركية مترجمة",
        "cat:https://www.moviz.land/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to
            "مسلسلات أجنبية",
        "cat:https://www.moviz.land/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to
            "مسلسلات كرتون",
        "cat:https://www.moviz.land/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2026/" to
            "مسلسلات رمضان 2026"
    )

    // ---------------------------------------------------------------
    // Title parsing
    // ---------------------------------------------------------------
    private data class EpInfo(val series: String, val season: Int, val episode: Int)

    private val seasonWords = mapOf(
        "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3,
        "الرابع" to 4, "الخامس" to 5, "السادس" to 6, "السابع" to 7,
        "الثامن" to 8, "التاسع" to 9, "العاشر" to 10,
        "الحادي عشر" to 11, "الثاني عشر" to 12
    )

    private fun cleanHtml(s: String): String =
        runCatching { Jsoup.parse(s).text() }.getOrDefault(s).trim()

    /** «مشاهدة مسلسل X الموسم الثاني الحلقة 5 …» → (X, 2, 5).
     *  Also accepts the short search-card form «X الحلقة 5 مدبلجة». */
    private fun parseEpTitle(raw: String): EpInfo? {
        var t = cleanHtml(raw).removePrefix("مشاهدة").trim()
        if (t.startsWith("مسلسل")) t = t.removePrefix("مسلسل").trim()
        val epM = Regex("""الحلقة\s+(\d+)""").find(t) ?: return null
        val ep = epM.groupValues[1].toIntOrNull() ?: return null
        var season = 1
        val sM = Regex("""الموسم\s+(\d+|[^\s]+)""").find(t)
        if (sM != null) {
            val sv = sM.groupValues[1]
            season = sv.toIntOrNull() ?: seasonWords[sv] ?: 1
        }
        var series = t.substring(0, epM.range.first).trim()
        val sIdx = series.indexOf("الموسم")
        if (sIdx >= 0) series = series.substring(0, sIdx).trim()
        return if (series.isBlank()) null else EpInfo(series, season, ep)
    }

    private fun cardDisplayName(rawTitle: String): Pair<String, Boolean> {
        val t = cleanHtml(rawTitle)
        val isSeries = t.contains("مسلسل") ||
            Regex("""الحلقة\s+\d+""").containsMatchIn(t)
        if (isSeries) {
            val info = parseEpTitle(t)
            if (info != null) return info.series to true
            return t.removePrefix("مشاهدة").trim() to true
        }
        return t.removePrefix("مشاهدة").trim()
            .removePrefix("فيلم").trim() to false
    }

    private fun toCard(rawTitle: String, url: String, poster: String?): SearchResponse? {
        if (url.isBlank()) return null
        val (display, isSeries) = cardDisplayName(rawTitle)
        if (display.isBlank()) return null
        return if (isSeries) {
            newTvSeriesSearchResponse(display, url) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(display, url) {
                this.posterUrl = poster
            }
        }
    }

    private fun dataCard(el: Element): SearchResponse? {
        val url = el.attr("data-url")
        val title = el.attr("data-title")
        val poster = el.attr("data-image").ifBlank { null }
        return toCard(title, url, poster)
    }

    // ---------------------------------------------------------------
    // Home / listings
    // ---------------------------------------------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = request.data
        return when {
            data.startsWith("hub:") -> {
                if (page > 1) return newHomePageResponse(emptyList(), false)
                val hub = data.removePrefix("hub:")
                val doc = app.get("$mainUrl/$hub/", headers = uaHeaders).document
                val lists = mutableListOf<HomePageList>()
                for (sec in doc.select(".movie-section").take(7)) {
                    val heading = sec.selectFirst(".movie-section-heading")
                        ?.text()?.trim()
                        ?.removeSuffix("عرض الكل")?.trim().orEmpty()
                    if (heading.isBlank()) continue
                    val cards = sec.select(".movie-slide")
                        .mapNotNull { dataCard(it) }.take(24)
                    if (cards.isNotEmpty()) {
                        lists += HomePageList(heading, cards, isHorizontalImages = true)
                    }
                }
                newHomePageResponse(lists, false)
            }
            data.startsWith("cat:") -> {
                val base = data.removePrefix("cat:")
                val url = if (page <= 1) base else base + "page/$page/"
                val doc = app.get(url, headers = uaHeaders).document
                val cards = doc.select(".archive-card")
                    .mapNotNull { dataCard(it) }
                val hasNext = doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
                newHomePageResponse(
                    listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
                    hasNext
                )
            }
            else -> newHomePageResponse(emptyList(), false)
        }
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        runCatching {
            val doc = app.get("$mainUrl/?s=$q", headers = uaHeaders).document
            for (card in doc.select(".search-card")) {
                val a = card.selectFirst("a[href]") ?: continue
                val url = a.attr("href")
                val img = card.selectFirst("img")
                val poster = img?.attr("src")?.ifBlank { null }
                val title = card.selectFirst(".search-card-title")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: a.attr("aria-label").trim()
                if (title.isNullOrBlank()) continue
                toCard(title, url, poster)?.let { out += it }
            }
        }
        if (out.isEmpty()) {
            // Fallback: wp-json search (titles/links only, no posters).
            runCatching {
                val text = app.get(
                    "$mainUrl/wp-json/wp/v2/posts?search=$q&per_page=30" +
                        "&_fields=title,link",
                    headers = uaHeaders
                ).text
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optJSONObject("title")?.optString("rendered").orEmpty()
                    val link = o.optString("link")
                    toCard(title, link, null)?.let { out += it }
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // Load (movie / series page)
    // ---------------------------------------------------------------
    private suspend fun wpJson(path: String): String? = runCatching {
        app.get("$mainUrl/wp-json/wp/v2/$path", headers = uaHeaders).text
    }.getOrNull()

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast('/')
        var poster: String? = null
        var plot: String? = null
        var pageTitle: String? = null
        runCatching {
            val doc = app.get(url, headers = uaHeaders).document
            poster = doc.selectFirst("meta[property=og:image]")
                ?.attr("content")?.ifBlank { null }
            plot = doc.selectFirst("meta[property=og:description]")
                ?.attr("content")?.ifBlank { null }
            pageTitle = doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
        }

        val postText = wpJson("posts?slug=$slug&_fields=id,title,tags,excerpt")
        val post = postText?.let {
            runCatching { JSONArray(it).optJSONObject(0) }.getOrNull()
        }
        val rawTitle = post?.optJSONObject("title")
            ?.optString("rendered")?.takeIf { it.isNotBlank() }
            ?: pageTitle ?: "MovizLand"
        if (plot.isNullOrBlank()) {
            plot = post?.optJSONObject("excerpt")
                ?.optString("rendered")?.let { cleanHtml(it) }
                ?.ifBlank { null }
        }

        // Series? Find the «تحميل مسلسل …» tag among the post's tags.
        var seriesTagId = 0
        var seriesName: String? = null
        val tagIds = mutableListOf<Int>()
        post?.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) tagIds += arr.optInt(i)
        }
        if (tagIds.isNotEmpty()) {
            val tagsText = wpJson(
                "tags?include=${tagIds.joinToString(",")}&per_page=100&_fields=id,name"
            )
            runCatching {
                val arr = JSONArray(tagsText ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val nm = cleanHtml(o.optString("name"))
                    if (nm.startsWith("تحميل مسلسل")) {
                        seriesTagId = o.optInt("id")
                        seriesName = nm.removePrefix("تحميل مسلسل").trim()
                        break
                    }
                }
            }
        }

        if (seriesTagId > 0 && !seriesName.isNullOrBlank()) {
            val eps = mutableListOf<Episode>()
            val seen = HashSet<String>()
            for (p in 1..12) {
                val text = wpJson(
                    "posts?tags=$seriesTagId&per_page=100&page=$p&_fields=link,title"
                ) ?: break
                val arr = runCatching { JSONArray(text) }.getOrNull() ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val link = o.optString("link")
                    val title = o.optJSONObject("title")
                        ?.optString("rendered").orEmpty()
                    if (link.isBlank() || !seen.add(link)) continue
                    val info = parseEpTitle(title)
                    eps += newEpisode(link) {
                        this.name = "الحلقة"
                        this.season = info?.season ?: 1
                        this.episode = info?.episode ?: (eps.size + 1)
                    }
                }
            }
            val seasons = eps.map { it.season ?: 1 }.distinct()
            val sorted = eps.sortedWith(
                compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE })
            ).map { ep ->
                newEpisode(ep.data) {
                    this.name = if (seasons.size > 1) {
                        "الموسم ${ep.season ?: 1} — الحلقة ${ep.episode ?: ""}"
                    } else {
                        "الحلقة ${ep.episode ?: ""}"
                    }
                    this.season = ep.season
                    this.episode = ep.episode
                }
            }
            return newTvSeriesLoadResponse(
                seriesName!!, url, TvType.TvSeries, sorted
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
            }
        }

        // Episode post without the series tag: group via wp-json
        // search on the parsed series name.
        val info = parseEpTitle(rawTitle)
        if (info != null) {
            val q = java.net.URLEncoder.encode(info.series, "UTF-8")
            val found = mutableListOf<Episode>()
            val seen = HashSet<String>()
            for (p in 1..3) {
                val text = wpJson(
                    "posts?search=$q&per_page=100&page=$p&_fields=link,title"
                ) ?: break
                val arr = runCatching { JSONArray(text) }.getOrNull() ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val link = o.optString("link")
                    val title = o.optJSONObject("title")
                        ?.optString("rendered").orEmpty()
                    val inf = parseEpTitle(title) ?: continue
                    if (inf.series != info.series) continue
                    if (link.isBlank() || !seen.add(link)) continue
                    found += newEpisode(link) {
                        this.name = "الحلقة ${inf.episode}"
                        this.season = inf.season
                        this.episode = inf.episode
                    }
                }
            }
            if (found.size >= 2) {
                val sorted = found.sortedWith(
                    compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE })
                )
                return newTvSeriesLoadResponse(
                    info.series, url, TvType.TvSeries, sorted
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = plot
                }
            }
        }

        val (display, _) = cardDisplayName(rawTitle)
        return newMovieLoadResponse(display, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------
    private val digHosts = listOf("1vid.xyz", "vidspeed.org")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val watchUrl = data.trimEnd('/') + "/watch/"
        val doc = runCatching {
            app.get(watchUrl, headers = uaHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Moviz", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashMap<String, String>()
        for (card in doc.select(".server-card")) {
            val src = card.attr("data-src")
            if (src.startsWith("http") && !embeds.containsKey(src)) {
                embeds[src] = card.attr("aria-label").ifBlank { hostOf(src) }
            }
        }
        var emitted = 0
        var extCount = 0
        var digCount = 0
        var upCount = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        for ((src, label) in embeds) {
            val host = hostOf(src)
            when {
                host.contains("upbom") -> {
                    val directs = digUpbom(src, watchUrl)
                    for (d in directs) {
                        emitDirect(d.second, d.first, src, counting)
                        upCount++
                    }
                }
                digHosts.any { host.contains(it) } -> {
                    val directs = digAllForDirects(src, watchUrl)
                    for (d in directs) {
                        emitDirect(label, d, src, counting)
                        digCount++
                    }
                }
                else -> {
                    val before = emitted
                    runCatching {
                        loadExtractor(src, watchUrl, subtitleCallback, counting)
                    }
                    if (emitted > before) extCount++
                }
            }
        }
        if (emitted == 0) {
            val secs = (System.currentTimeMillis() - t0) / 1000
            emitDebug(
                listOf(
                    "Moviz", "srv=${embeds.size}", "ext=$extCount",
                    "dig=$digCount", "up=$upCount", "t=${secs}s"
                ),
                callback
            )
        }
        return true
    }

    /** upbom embed page lists direct mp4s (per quality) in plain HTML. */
    private suspend fun digUpbom(
        embed: String,
        referer: String
    ): List<Pair<String, String>> {
        val text = fetchTextA(embed, referer) ?: return emptyList()
        val out = LinkedHashMap<String, Pair<String, String>>()
        Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").findAll(text).forEach { m ->
            val u = m.value.trimEnd('\\')
            val q = Regex("""(360|480|720|1080)p""").find(u)
                ?.groupValues?.get(1) ?: "HD"
            out.putIfAbsent(q, "موفيز لاند ${q}p" to u)
        }
        return out.values.toList()
    }

    // ---------------------------------------------------------------
    // Dig machinery (ported from TurkDub — packed-JS unpacker etc.)
    // ---------------------------------------------------------------
    private suspend fun fetchTextA(url: String, referer: String?): String? =
        runCatching {
            val h = LinkedHashMap<String, String>()
            h["User-Agent"] = browserUA
            h["Accept"] = "text/html,application/xhtml+xml,*/*;q=0.8"
            if (!referer.isNullOrBlank()) h["Referer"] = asciiSafe(referer)
            app.get(url, headers = h).text
        }.getOrNull()

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
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean).forEach {
            add(it.value)
        }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean).forEach {
            add(it.value)
        }
        Regex("""["'](//[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)["']""")
            .findAll(clean).forEach {
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
            out += Regex("""\b\w+\b""").replace(payload) { mr ->
                map[mr.value] ?: mr.value
            }
        }
        return out
    }

    private suspend fun digAllForDirects(
        url: String,
        referer: String?
    ): List<String> {
        val doc = fetchTextA(url, referer) ?: return emptyList()
        val out = LinkedHashSet<String>()
        out += extractAllDirects(doc)
        for (iframe in Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
            .findAll(doc)) {
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
                val inner = fetchTextA(abs, url)
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
}
