package com.mycima

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * MyCima (vid.mycima.cc, "Cimawbas") provider for CloudStream 3.
 *
 * Built against the live structure verified on 2026-10-15:
 * - PHP template site (3arbserv/PHP-Melody family): content is watch.php?vid={id}
 *   for BOTH movies and episodes; series hubs are themselves watch pages whose
 *   anchor list (title attributes) carries the episodes of the series.
 * - Listings: movies.php, all-series.php, episodes.php, newvideos.php,
 *   topvideos.php and category.php?cat={slug}; all paginate with &page={n}
 *   (or ?page={n}); search is search.php?keywords={q}.
 * - Cards are anchors to watch.php carrying a title attribute and a lazy
 *   poster in the child img's data-echo attribute.
 * - Playback (traced end-to-end before writing this): the watch page's
 *   play.php?vid={id} lists every server as a direct embed URL on
 *   extractor-known hosts (VidMoly, MixDrop, Byse, HGCloud/StreamHG,
 *   Doodstream, ...). No unlock, no session, no protected flow. The site's
 *   own default embed (vipserver.liiivideo.com) hangs from datacenter IPs
 *   and has no known extractor, so it is only used when it is the sole
 *   candidate. Every request carries an Android-Chrome header set.
 */
class MyCimaProvider : MainAPI() {
    override var mainUrl = "https://vid.mycima.cc"
    override var name = "MyCima"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    // Sections mirror the site's own portals and category menu.
    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php" to "المضاف حديثا",
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/all-series.php" to "أحدث المسلسلات",
        "$mainUrl/episodes.php" to "أحدث الحلقات",
        "$mainUrl/topvideos.php" to "الأكثر مشاهدة",
        "$mainUrl/category.php?cat=cimawbas-movies-online" to "أفلام",
        "$mainUrl/category.php?cat=all-english-movies" to "أفلام أجنبية",
        "$mainUrl/category.php?cat=5-cimawbas-aflam-3arby" to "أفلام عربية",
        "$mainUrl/category.php?cat=indian-movies-cimawbas" to "أفلام هندية",
        "$mainUrl/category.php?cat=the-korean-movies" to "أفلام كورية",
        "$mainUrl/category.php?cat=turkey-movie" to "أفلام تركية",
        "$mainUrl/category.php?cat=Asian-movies" to "أفلام آسيوية",
        "$mainUrl/category.php?cat=the-anime-movies-cimawbas" to "أفلام أنمي وكرتون",
        "$mainUrl/category.php?cat=Movies-2026" to "أفلام 2026",
        "$mainUrl/category.php?cat=watch-series-online-cimawbas" to "مسلسلات",
        "$mainUrl/category.php?cat=english-series-1" to "مسلسلات أجنبية",
        "$mainUrl/category.php?cat=1-cimawbas-mosalsalat-3araby" to "مسلسلات عربية",
        "$mainUrl/category.php?cat=moslslat-turkeya" to "مسلسلات تركية",
        "$mainUrl/category.php?cat=korean-series" to "مسلسلات كورية",
        "$mainUrl/category.php?cat=Asian-series" to "مسلسلات آسيوية",
        "$mainUrl/category.php?cat=animition-cimawbas" to "مسلسلات أنمي",
    )

    // ---------- helpers ----------

    private fun cleanTitle(raw: String): String = raw
        .trim()
        .removePrefix("مشاهدة")
        .trim()
        .replace(Regex("""\s*\|.*$"""), "")
        .trim()

    private fun isAnimeTitle(title: String): Boolean =
        title.contains("انمي") || title.contains("أنمي") || title.contains("كرتون")

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.contains("watch.php?vid=")) return null
        val title = cleanTitle(attr("title").ifBlank { text() })
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-echo"))
            ?: fixUrlNull(img?.attr("data-src"))
            ?: fixUrlNull(img?.attr("src"))?.takeIf { !it.contains("blank") && !it.contains("pixel") }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val isSeries = title.contains("مسلسل") || title.contains("الحلقة") || title.contains("الموسم")
        return when {
            isAnimeTitle(title) && isSeries ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            isAnimeTitle(title) ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            isSeries ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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

    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("a[href*=\"watch.php?vid=\"]").forEach { a ->
            val item = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            // Keep the richest variant (the one carrying a poster).
            val existing = seen[item.url]
            if (existing == null || (existing.posterUrl == null && item.posterUrl != null)) {
                seen[item.url] = item
            }
        }
        return seen.values.toList()
    }

    private val seasonOrdinals = mapOf(
        "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
        "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
        "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12
    )

    private fun seasonOf(title: String): Int {
        Regex("""الموسم\s+(\d{1,2})""").find(title)?.let {
            return it.groupValues[1].toIntOrNull() ?: 1
        }
        for ((word, num) in seasonOrdinals) {
            if (title.contains("الموسم $word") || title.contains("موسم $word")) return num
        }
        return 1
    }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else request.data + (if (request.data.contains("?")) "&page=$page" else "?page=$page")
        val document = app.get(url, headers = browserHeaders).document
        val items = document.parseCards()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl + "/search.php?keywords=" + java.net.URLEncoder.encode(query, "UTF-8")
        return app.get(url, headers = browserHeaders).document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
        val rawTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.title()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) throw ErrorLoadingException("No title")
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.trim()?.ifBlank { null }

        // A series hub lists its episodes as watch anchors whose titles carry
        // "الحلقة N" plus the series' core name (recommendations on the same
        // page name other series, so the shared name is the filter).
        val core = title
            .removePrefix("مسلسل").trim()
            .replace(Regex("""الموسم\s+.*$"""), "").trim()
        val episodes = mutableListOf<Episode>()
        val seenEp = HashSet<String>()
        document.select("a[href*=\"watch.php?vid=\"]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (href == url || !seenEp.add(href)) return@forEach
            val epTitle = cleanTitle(a.attr("title").ifBlank { a.text() })
            if (!epTitle.contains("الحلقة")) return@forEach
            if (core.isNotBlank() && !epTitle.contains(core, ignoreCase = true)) return@forEach
            val epNum = Regex("""الحلقة\s+(\d{1,4})""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            episodes += newEpisode(href) {
                this.name = epNum?.let { "الحلقة $it" } ?: epTitle
                this.season = seasonOf(epTitle)
                this.episode = epNum
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        return if (episodes.size >= 2) {
            newTvSeriesLoadResponse(
                title, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(
                title, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Field diagnostics: stage markers are surfaced as one playable DEBUG
        // source when nothing playable is found.
        val diag = mutableListOf<String>()
        val vid = Regex("""vid=([a-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            diag += "vid:missing"
            emitDebug(diag, callback)
            return true
        }

        val playUrl = "$mainUrl/play.php?vid=$vid"
        val res = runCatching { app.get(playUrl, headers = browserHeaders, referer = data) }.getOrNull()
        diag += "play=${res?.code ?: "err"}"

        val candidates = LinkedHashSet<String>()
        if (res != null) {
            val document = res.document
            document.select("iframe[src]").forEach {
                val src = it.attr("src").trim()
                if (src.startsWith("http") && !src.contains(mainUrl)) candidates += fixUrl(src)
            }
            document.select("a[href]").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http") && !href.startsWith(mainUrl) &&
                    (href.contains("/e/") || href.contains("/embed"))
                ) candidates += fixUrl(href)
            }
            // The server list is also rendered as raw URLs in the page body.
            Regex("""https?://[^\s"'<>\\]+""").findAll(res.text).forEach { m ->
                val u = m.value
                if (!u.startsWith(mainUrl) && (u.contains("/e/") || u.contains("/embed")) &&
                    !u.contains("google") && !u.contains("facebook") && !u.contains("aglo") &&
                    !u.contains(".js") && !u.contains(".css") && !u.contains(".png") && !u.contains(".jpg")
                ) candidates += fixUrl(u)
            }
        }
        diag += "embeds=${candidates.size}"

        // Extractor-friendly hosts first; the site's own vipserver and other
        // unknown shells last (and skipped entirely when a known host exists).
        val priorityHosts = listOf(
            "vidmoly", "mixdrop", "byse", "hgcloud", "streamhg", "doodstream",
            "streamwish", "uqload", "voe", "filemoon", "streamtape", "vidhide", "earnvids"
        )
        val known = candidates.filter { c -> priorityHosts.any { c.contains(it) } }
        val ordered = if (known.isNotEmpty()) known + (candidates - known.toSet())
            .filter { !it.contains("liiivideo") && !it.contains("minochinos") }
        else candidates.toList()

        var found = false
        for (embed in ordered) {
            if (loadExtractor(embed, data, subtitleCallback, callback)) {
                found = true
            }
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
}
