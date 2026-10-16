package com.akwam

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
 * Akwam provider for CloudStream 3.
 *
 * Built against the live structure of https://akwams.org verified on 2026-10-15:
 * - WordPress (theme "akwam"). Cards are .entry-box blocks: .entry-image a.box
 *   wraps a lazy <img data-src> whose alt carries the full Arabic title.
 * - Movies and single episodes are root-level posts (Arabic slugs); series
 *   hubs live at /series/{slug}/ and link their episode posts.
 * - Archives paginate WordPress-style: {section}/page/{n}/.
 * - Search is /?s={query}.
 * - Watching needs no unlock/session: the post page's watch button simply
 *   navigates to {post}/watch, whose HTML carries the player iframe plus
 *   [data-link] server mirrors on extractor-known hosts (MixDrop,
 *   Doodstream, ...). The full chain was traced end-to-end from the
 *   sandbox before this provider was written.
 * - Every request carries an Android-Chrome header set (the lesson of the
 *   EgyDead HTTP-520 saga: never ship the HTTP library's default agent).
 */
class AkwamProvider : MainAPI() {
    override var mainUrl = "https://akwams.org"
    override var name = "Akwam"
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
        "$mainUrl/recent" to "المضاف حديثا",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/shows" to "برامج",
        "$mainUrl/tv" to "تلفزيون",
        "$mainUrl/mix" to "منوعات",
        "$mainUrl/category/movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a" to "أفلام أجنبية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a" to "أفلام عربية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "أفلام تركية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "أفلام هندية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a" to "أفلام أنمي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86" to "أفلام كرتون",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a" to "مسلسلات أجنبية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9" to "مسلسلات آسيوية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9" to "مسلسلات تركية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a" to "مسلسلات أنمي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2026" to "مسلسلات رمضان 2026",
    )

    // ---------- helpers ----------

    private fun decode(url: String): String = runCatching {
        java.net.URLDecoder.decode(url, "UTF-8")
    }.getOrDefault(url)

    private fun cleanTitle(raw: String): String =
        raw.trim().removePrefix("مشاهدة").trim()

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".entry-image a") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/category/") || href.contains("/wp-") ||
            href.endsWith("/login") || href.endsWith("/recent") ||
            href.endsWith("/movies") || href.endsWith("/series") ||
            href.endsWith("/shows") || href.endsWith("/tv") || href.endsWith("/mix")
        ) return null

        val img = selectFirst("img")
        val title = cleanTitle(img?.attr("alt") ?: selectFirst(".entry-body a")?.text() ?: "")
        if (title.isBlank()) return null
        var poster = fixUrlNull(img?.attr("data-src"))
        if (poster == null) {
            val src = fixUrlNull(img?.attr("src"))
            if (src != null && !src.endsWith("load.jpg")) poster = src
        }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val decoded = decode(href)
        val isAnime = decoded.contains("انمي") || decoded.contains("أنمي") || decoded.contains("كرتون")

        return when {
            href.contains("/series/") && isAnime ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            href.contains("/series/") ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            isAnime ->
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

    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select(".entry-box").forEach { box ->
            val item = runCatching { box.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(item.url, item)
        }
        return seen.values.toList()
    }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst("a[data-fancybox] img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst(".entry-image img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))

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
        val title = cleanTitle(
            document.selectFirst("h1.entry-title")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?: throw ErrorLoadingException("No title")
        )
        val decoded = decode(url)
        val isAnime = decoded.contains("انمي") || decoded.contains("أنمي") || decoded.contains("كرتون")

        return if (url.contains("/series/")) {
            // Series hub: episode posts are root-level links whose decoded
            // slug carries "الحلقة N".
            val byNumber = sortedMapOf<Int, Episode>()
            val unnamed = mutableListOf<Episode>()
            document.select("a[href]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                if (!href.startsWith(mainUrl) || href == url) return@forEach
                val dec = decode(href)
                if (!dec.contains("الحلقة")) return@forEach
                val num = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(dec)?.groupValues?.get(1)?.toIntOrNull()
                val ep = newEpisode(href) {
                    this.name = num?.let { "الحلقة $it" } ?: a.text().trim()
                    this.season = 1
                    this.episode = num
                }
                if (num != null) byNumber.putIfAbsent(num, ep) else unnamed += ep
            }
            val episodes = byNumber.values.toList() + unnamed
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes")
            newTvSeriesLoadResponse(
                title, url,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = document.pagePoster()
                this.plot = document.pagePlot()
            }
        } else {
            newMovieLoadResponse(
                title, url,
                if (isAnime) TvType.Anime else TvType.Movie,
                url
            ) {
                this.posterUrl = document.pagePoster()
                this.plot = document.pagePlot()
            }
        }
    }

    /** Scans a page's text for a direct media URL. */
    private fun extractDirect(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(clean)?.let { return it.value }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(clean)?.let { return it.value }
        return null
    }

    /** Emits one direct media URL found inside an embed page. */
    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                name, name, url,
                if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    // Dean Edwards packer blocks: eval(function(p,a,c,k,e,d){...}('payload',
    // radix, count, 'k1|k2|...'.split('|'))). Some embed hosts hide their
    // player config this way; unpacked, it carries a plain file:"..." URL.
    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Unpacks every packed-JS block in the page; returns the plain texts. */
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
     * Stage 2 (phone-side): fetch an embed page the built-in extractors
     * couldn't handle and pull the direct file out of it — scanning the raw
     * page first, then the unpacked form of any packed-JS player config.
     */
    private suspend fun scanEmbed(
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = runCatching {
            app.get(embed, headers = browserHeaders, referer = data).text
        }.getOrNull() ?: return false
        val direct = extractDirect(text)
            ?: unpackPackedJs(text).firstNotNullOfOrNull { extractDirect(it) }
            ?: return false
        if (direct.isBlank()) return false
        emitDirect(direct, embed, callback)
        return true
    }

    /** Stage 3 (server-side): the relay's generic resolver dig. */
    private suspend fun resolveViaRelay(
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val relayUrl = "https://egydead-stremio.onrender.com/relay/resolve?url=" +
            java.net.URLEncoder.encode(embed, "UTF-8") +
            "&ref=" + java.net.URLEncoder.encode(data, "UTF-8")
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(relayUrl).text }.getOrNull()
            if (text != null) break
            Thread.sleep(2000)
        }
        val body = text ?: return 0
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return 0
        if (!json.optBoolean("ok")) return 0
        val arr = json.optJSONArray("direct") ?: return 0
        var emitted = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val link = item.optString("url")
            if (link.isNotBlank()) {
                emitDirect(link, embed, callback)
                emitted++
            }
        }
        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Field diagnostics: stage markers are surfaced as one playable DEBUG
        // source when nothing playable is found, so any failure can be read
        // off the player's source list in one shot.
        val diag = mutableListOf<String>()
        val watchUrl = data.trimEnd('/') + "/watch"
        val res = runCatching { app.get(watchUrl, headers = browserHeaders, referer = data) }.getOrNull()
        diag += "watch=${res?.code ?: "err"}"
        if (res == null) {
            emitDebug(diag, callback)
            return true
        }
        val document = res.document
        val embeds = LinkedHashSet<String>()
        document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http")) embeds += fixUrl(src)
        }
        document.select("[data-link]").forEach {
            val link = it.attr("data-link").trim()
            if (link.startsWith("http")) embeds += fixUrl(link)
        }
        document.select("a[href]").forEach {
            val href = it.attr("href").trim()
            if (href.startsWith("http") && !href.startsWith(mainUrl) &&
                Regex("""(embed|/e/|/v/|player|watch)""").containsMatchIn(href)
            ) embeds += fixUrl(href)
        }
        diag += "embeds=${embeds.size}"

        var found = false
        val unresolved = mutableListOf<String>()
        for (embed in embeds) {
            if (loadExtractor(embed, data, subtitleCallback, callback)) {
                found = true
            } else {
                unresolved += embed
            }
        }
        // Stage 2: dig the direct file out of each rejected embed page on
        // the phone (raw + packed-JS forms) — the generalized lesson of the
        // AnimeZid MegaMax miss: a chain is not done when it merely holds
        // links, only when a terminal playable exists.
        if (!found) {
            var scanned = 0
            for (embed in unresolved) {
                if (scanEmbed(embed, data, callback)) {
                    scanned++
                    found = true
                }
            }
            diag += "scan=$scanned"
        }
        // Stage 3: the relay server takes the same dig from its side.
        if (!found) {
            var relayed = 0
            for (embed in unresolved.take(3)) {
                relayed += resolveViaRelay(embed, data, callback)
            }
            diag += "relay=$relayed"
            if (relayed > 0) found = true
        }
        if (!found) {
            // Last resort: a direct file on the watch page itself.
            val text = res.text
            val direct = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(text)?.value
            if (direct != null) {
                callback(
                    newExtractorLink(
                        name, name, direct,
                        if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
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
