package com.wecima

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
 * WeCima provider for CloudStream 3.
 *
 * Built against the live structure of https://wecima.gripe verified on 2026-10-15:
 * - WordPress (MyCima-family theme). Cards are .GridItem blocks: the
 *   .Thumb--GridItem anchor carries href + title; the poster hides in a
 *   span's data-lazy-style CSS variable.
 * - Movies and episodes are root-level posts (Arabic slugs); series hubs at
 *   /series/{slug}/ list every episode post directly (the /season/{id}
 *   links the theme prints are dead — 404 — and are ignored).
 * - Archives paginate WordPress-style: {base}page/{n}/. Search is /?s={q}.
 * - The post page renders its watch servers as li[data-watch] inside
 *   .WatchArea (plus a download list of token links). On this domain each
 *   post carries a single proprietary player host (akhbarworld.online).
 *   v1 scanned its page for a direct .m3u8/.mp4 and missed on-device
 *   (page=200 | servers=1 | scan=miss): the host is an ArtPlayer page that
 *   carries the stream as a JS constant — const videoUrl = "..." — either
 *   an escaped-slash HLS master or an extensionless redirector URL from
 *   which the player derives v.mp4 quality variants. v2 extracts exactly
 *   that constant (with a retry; the host is flaky), falling back to the
 *   generic extension sweep. DEBUG markers: page/servers/vu/scan.
 * - v3 (2026-10-15): v2's readout on the user's phone was an ExoPlayer
 *   ERROR_CODE_IO_BAD_HTTP_STATUS on the emitted redirector URL — the
 *   phone DID receive the real stream URL, but the redirector (a
 *   Cloudflare Worker) answered the player's plain fetch with an error
 *   status (the same URL returns HTTP 500 "error code: 1101" from
 *   datacenter egresses in every header variant, while the site's own
 *   browser player plays it). v3 therefore (a) probes the stream URL
 *   from inside loadLinks with a full browser media-fetch header set,
 *   recording probe=<code> and the final URL after redirects, (b) emits
 *   the probed final URL when it differs, and (c) arms every emitted
 *   stream link with the same browser header set so the player presents
 *   itself like the site's own player.
 * - Every request carries an Android-Chrome header set.
 */
class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.gripe"
    override var name = "WeCima"
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

    // Sections mirror the site's own tabs and category menus.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "المضاف حديثا",
        "$mainUrl/movies/" to "أفلام جديدة",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/series/" to "مسلسلات جديدة",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "أفلام أجنبية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "أفلام عربية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a/" to "أفلام هندية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/" to "أفلام آسيوية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a%d8%a9/" to "مسلسلات عربية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "مسلسلات تركية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "مسلسلات هندية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2026/" to "مسلسلات رمضان 2026",
    )

    // ---------- helpers ----------

    private fun decode(url: String): String = runCatching {
        java.net.URLDecoder.decode(url, "UTF-8")
    }.getOrDefault(url)

    private fun cleanTitle(raw: String): String = raw
        .trim()
        .removePrefix("مشاهدة")
        .trim()
        .replace(Regex("""\s*–\s*ماى سيما.*$"""), "")
        .replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "")
        .trim()

    private fun isAnime(decoded: String): Boolean =
        decoded.contains("انمي") || decoded.contains("أنمي") || decoded.contains("كرتون")

    private val seasonOrdinals = mapOf(
        "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
        "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
        "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12
    )

    private fun seasonOf(decoded: String): Int {
        Regex("""الموسم\s*-?\s*(\d{1,2})""").find(decoded)?.let {
            return it.groupValues[1].toIntOrNull() ?: 1
        }
        for ((word, num) in seasonOrdinals) {
            if (decoded.contains("الموسم-$word") || decoded.contains("الموسم $word")) return num
        }
        return 1
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".Thumb--GridItem a") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/category/") || href.contains("/wp-") ||
            href.endsWith("/movies/") || href.endsWith("/series/") ||
            href.endsWith("/episodes/") || href == "$mainUrl/"
        ) return null
        val title = cleanTitle(a.attr("title").ifBlank { a.text() })
        if (title.isBlank()) return null
        val style = selectFirst("span[data-lazy-style]")?.attr("data-lazy-style") ?: ""
        var poster = Regex("""url\((https?://[^)]+)\)""").find(style)?.groupValues?.get(1)
            ?.let { fixUrlNull(it) }
        if (poster == null) {
            val img = selectFirst("img")
            poster = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))
                ?.takeIf { !it.contains("load") && !it.contains("blank") }
        }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val decoded = decode(href)
        val anime = isAnime(decoded)
        val seriesLike = href.contains("/series/") || decoded.contains("مسلسل") || decoded.contains("الحلقة")
        return when {
            anime && seriesLike -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            seriesLike -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select(".GridItem").forEach { item ->
            val card = runCatching { item.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(card.url, card)
        }
        if (seen.isEmpty()) {
            // Fallback: any post anchor carrying a title.
            select("a[href][title]").forEach { a ->
                val card = runCatching { a.toCard() }.getOrNull() ?: return@forEach
                seen.putIfAbsent(card.url, card)
            }
        }
        return seen.values.toList()
    }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))

    private fun Document.pagePlot(): String? =
        selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()?.ifBlank { null }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val items = document.parseCards()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl + "/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        return app.get(url, headers = browserHeaders).document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
        val decoded = decode(url)
        val anime = isAnime(decoded)

        if (url.contains("/series/")) {
            val title = cleanTitle(
                document.selectFirst("h1")?.text()
                    ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                    ?: throw ErrorLoadingException("No title")
            )
            val episodes = mutableListOf<Episode>()
            val seen = HashSet<String>()
            document.select("a[href]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                if (!href.startsWith(mainUrl) || href == url || !seen.add(href)) return@forEach
                val dec = decode(href)
                if (!dec.contains("الحلقة")) return@forEach
                val num = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(dec)?.groupValues?.get(1)?.toIntOrNull()
                episodes += newEpisode(href) {
                    this.name = num?.let { "الحلقة $it" } ?: cleanTitle(a.attr("title"))
                    this.season = seasonOf(dec)
                    this.episode = num
                }
            }
            episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes")
            return newTvSeriesLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = document.pagePoster()
                this.plot = document.pagePlot()
            }
        }

        // An episode post delegates to its series hub when the page links one,
        // so the user lands on the full episode list.
        if (decoded.contains("الحلقة")) {
            val hub = document.select("a[href*=\"/series/\"]")
                .map { fixUrl(it.attr("href")) }
                .firstOrNull { it.contains("/series/") && it != url }
            if (hub != null) return load(hub)
        }

        val title = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?: throw ErrorLoadingException("No title")
        )
        return newMovieLoadResponse(
            title, url,
            if (anime) TvType.Anime else TvType.Movie,
            url
        ) {
            this.posterUrl = document.pagePoster()
            this.plot = document.pagePlot()
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
        val res = runCatching { app.get(data, headers = browserHeaders) }.getOrNull()
        diag += "page=${res?.code ?: "err"}"
        if (res == null) {
            emitDebug(diag, callback)
            return true
        }
        val document = res.document
        val servers = LinkedHashSet<String>()
        document.select("[data-watch]").forEach {
            val link = it.attr("data-watch").trim()
            if (link.startsWith("http")) servers += fixUrl(link)
        }
        document.select(".WatchArea iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http")) servers += fixUrl(src)
        }
        diag += "servers=${servers.size}"

        var found = false
        for (server in servers) {
            if (loadExtractor(server, data, subtitleCallback, callback)) {
                found = true
            }
        }

        if (!found) {
            // Scan each server page (and the download token pages) for a
            // direct playable file — the standard layout of such players.
            val scanTargets = LinkedHashSet<String>()
            scanTargets += servers
            document.select(".List--Download--Wecima--Single a[href]").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http")) scanTargets += fixUrl(href)
            }
            var scanned = false
            for (target in scanTargets) {
                var text: String? = null
                for (attempt in 1..2) {
                    text = runCatching {
                        app.get(target, headers = browserHeaders, referer = data).text
                    }.getOrNull()
                    if (text != null) break
                    Thread.sleep(1500)
                }
                val pageText = text ?: continue
                // The site's player page (ArtPlayer) carries the stream as a
                // JS constant: const videoUrl = "..." — either an HLS master
                // (with escaped slashes) or a redirector/file URL from which
                // the player itself derives v.mp4 quality variants. v1's
                // extension sweep missed both shapes (on-device scan=miss).
                val videoUrl = Regex("""videoUrl\s*=\s*"([^"]+)"""").find(pageText)
                    ?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
                    val isHls = videoUrl.contains(".m3u8")
                    // The header set a browser media fetch presents; the
                    // redirector behind videoUrl refuses plainer clients.
                    val streamHeaders = browserHeaders + mapOf(
                        "Accept" to "*/*",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "no-cors",
                        "Sec-Fetch-Site" to "cross-site"
                    )
                    if (isHls) {
                        callback(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                                this.referer = target
                                this.headers = streamHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        // Probe the stream from inside loadLinks: records
                        // what the phone's own client actually gets and
                        // where redirects land.
                        val probe = runCatching {
                            app.get(
                                videoUrl,
                                headers = streamHeaders + mapOf("Range" to "bytes=0-0"),
                                referer = target
                            )
                        }.getOrNull()
                        diag += "probe=${probe?.code ?: "err"}"
                        val finalUrl = probe?.url?.takeIf { it.startsWith("http") }
                        if (finalUrl != null && finalUrl != videoUrl) {
                            diag += "final=${finalUrl.substringBefore('?').take(60)}"
                            callback(
                                newExtractorLink(name, "$name مباشر", finalUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = target
                                    this.headers = streamHeaders
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                        callback(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = target
                                this.headers = streamHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        if (videoUrl.contains("v.mp4")) {
                            for ((label, quality) in listOf(
                                "1080" to Qualities.P1080, "720" to Qualities.P720,
                                "480" to Qualities.P480, "360" to Qualities.P360
                            )) {
                                callback(
                                    newExtractorLink(
                                        name, "$name ${label}p",
                                        videoUrl.replace("v.mp4", "$label.mp4"),
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = target
                                        this.headers = streamHeaders
                                        this.quality = quality.value
                                    }
                                )
                            }
                        }
                    }
                    diag += "vu:hit"
                    scanned = true
                    found = true
                    break
                }
                val direct = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageText)?.value
                    ?: Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(pageText)?.value
                if (direct != null) {
                    callback(
                        newExtractorLink(
                            name, name, direct,
                            if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = target
                            this.headers = browserHeaders + mapOf(
                                "Accept" to "*/*",
                                "Sec-Fetch-Dest" to "video",
                                "Sec-Fetch-Mode" to "no-cors",
                                "Sec-Fetch-Site" to "cross-site"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    scanned = true
                    found = true
                    break
                }
            }
            diag += "scan=${if (scanned) "hit" else "miss"}"
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
