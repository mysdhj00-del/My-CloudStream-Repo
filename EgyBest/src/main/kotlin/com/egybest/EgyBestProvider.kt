package com.egybest

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
 * EgyBest provider for CloudStream 3.
 *
 * Built against the live structure of https://egibest.cyou verified on 2026-10-15:
 * - WordPress. Cards are a.postBlock (home) and a.postBlockCol (archives):
 *   href + title attribute, poster in the child img's data-src (src is a
 *   placeholder). Movies and episodes are root-level posts; the site has no
 *   series hub pages — its /series/ archive lists episode posts directly —
 *   so an episode post loads as a single-episode series entry.
 * - Archives (/movies/, /series/, /trends/, /category/...) paginate
 *   WordPress-style: {base}page/{n}/. Search is /?s={q}.
 * - Playback (traced end-to-end before writing this): the post page renders
 *   its servers as li[onclick] calling loadIframe(this, '<site>/watch/?url=
 *   <BASE64>'); the base64 decodes to the real embed URL — MixDrop and
 *   HGCloud/StreamHG among them, both extractor-known — and the page's
 *   JSON-LD carries the primary embed as "embedUrl". No unlock, no session.
 * - Every request carries an Android-Chrome header set.
 */
class EgyBestProvider : MainAPI() {
    override var mainUrl = "https://egibest.cyou"
    override var name = "EgyBest"
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

    // Sections mirror the site's own archives and category menu.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "المضاف حديثا",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/trends/" to "الأكثر مشاهدة",
        "$mainUrl/category/movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to "أفلام أجنبية",
        "$mainUrl/category/movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "أفلام عربية",
        "$mainUrl/category/movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "أفلام هندية",
        "$mainUrl/category/movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "أفلام تركية",
        "$mainUrl/category/movies/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/" to "أفلام آسيوية",
        "$mainUrl/category/series/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to "مسلسلات أجنبية",
        "$mainUrl/category/series/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a%d8%a9/" to "مسلسلات عربية",
        "$mainUrl/category/series/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/" to "مسلسلات تركية",
        "$mainUrl/category/series/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "$mainUrl/category/anime/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to "مسلسلات كرتون",
        "$mainUrl/category/anime/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to "أفلام كرتون",
        "$mainUrl/category/%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%aa%d9%84%d9%81%d8%b2%d9%8a%d9%88%d9%86%d9%8a%d8%a9-tv-shows/" to "برامج تلفزيونية",
    )

    // ---------- helpers ----------

    private fun decode(url: String): String = runCatching {
        java.net.URLDecoder.decode(url, "UTF-8")
    }.getOrDefault(url)

    private fun cleanTitle(raw: String): String = raw
        .trim()
        .removePrefix("مشاهدة")
        .trim()
        .replace(Regex("""\s*\|\s*Egybest.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*–\s*ايجى بست.*$"""), "")
        .trim()

    private fun isAnime(text: String): Boolean =
        text.contains("انمي") || text.contains("أنمي") || text.contains("كرتون") || text.contains("انمى")

    /** Minimal standard-alphabet base64 decoder (avoids classpath surprises). */
    private fun decodeBase64(input: String): String? {
        val clean = input.trim().replace('-', '+').replace('_', '/').filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = table.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return runCatching {
            String(out.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/category/") || href.contains("/wp-") ||
            href.endsWith("/movies/") || href.endsWith("/series/") ||
            href.endsWith("/trends/") || href == "$mainUrl/"
        ) return null
        val title = cleanTitle(attr("title").ifBlank { selectFirst("h3.title")?.text() ?: text() })
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src"))?.takeIf { !it.contains("src-default") }
            ?: fixUrlNull(img?.attr("src"))?.takeIf { !it.contains("src-default") }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val probe = decode(href) + " " + title
        val seriesLike = probe.contains("مسلسل") || probe.contains("الحلقة")
        return when {
            isAnime(probe) && seriesLike -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            isAnime(probe) -> newAnimeSearchResponse(title, href, TvType.Anime) {
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
        select("a.postBlock, a.postBlockCol").forEach { a ->
            val card = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(card.url, card)
        }
        return seen.values.toList()
    }

    private val seasonOrdinals = mapOf(
        "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
        "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
        "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12
    )

    private fun seasonOf(text: String): Int {
        Regex("""الموسم\s*-?\s*(\d{1,2})""").find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 1
        }
        for ((word, num) in seasonOrdinals) {
            if (text.contains("الموسم $word") || text.contains("الموسم-$word")) return num
        }
        return 1
    }

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
        val rawTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.title()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) throw ErrorLoadingException("No title")
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.trim()?.ifBlank { null }
        val anime = isAnime(decoded + " " + title)

        return if (decoded.contains("الحلقة") || title.contains("الحلقة")) {
            val epNum = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(decoded + " " + title)
                ?.groupValues?.get(1)?.toIntOrNull()
            val episode = newEpisode(url) {
                this.name = epNum?.let { "الحلقة $it" } ?: title
                this.season = seasonOf(decoded + " " + title)
                this.episode = epNum
            }
            newTvSeriesLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                listOf(episode)
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else if (decoded.contains("مسلسل") || title.contains("مسلسل")) {
            // A series post without an episode number: expose it as one entry.
            val episode = newEpisode(url) {
                this.name = title
                this.season = seasonOf(decoded + " " + title)
                this.episode = 1
            }
            newTvSeriesLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                listOf(episode)
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
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
        // source when nothing playable is found.
        val diag = mutableListOf<String>()
        val res = runCatching { app.get(data, headers = browserHeaders) }.getOrNull()
        diag += "page=${res?.code ?: "err"}"
        if (res == null) {
            emitDebug(diag, callback)
            return true
        }
        val text = res.text
        val document = res.document

        val embeds = LinkedHashSet<String>()
        // Primary embed from the JSON-LD block.
        Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let {
            if (it.startsWith("http")) embeds += fixUrl(it)
        }
        // Server items: loadIframe(this, '<site>/watch/?url=<base64>').
        Regex("""loadIframe\(\s*this\s*,\s*'([^']+)'\s*\)""").findAll(text).forEach { m ->
            val wrapper = m.groupValues[1]
            val encoded = wrapper.substringAfter("url=", "")
            if (encoded.isNotBlank()) {
                val decoded = decodeBase64(decode(encoded))
                if (decoded != null && decoded.startsWith("http")) embeds += fixUrl(decoded)
                else if (wrapper.startsWith("http") && !wrapper.startsWith(mainUrl)) {
                    embeds += fixUrl(wrapper)
                }
            }
        }
        // Any iframe the page renders directly.
        document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.startsWith(mainUrl)) embeds += fixUrl(src)
        }
        diag += "servers=${embeds.size}"

        val priorityHosts = listOf(
            "mixdrop", "hgcloud", "streamhg", "vidmoly", "byse", "doodstream",
            "streamwish", "uqload", "voe", "filemoon", "vidhide", "earnvids"
        )
        val ordered = embeds.sortedByDescending { e -> priorityHosts.any { e.contains(it) } }
        var found = false
        val unresolved = mutableListOf<String>()
        for (embed in ordered) {
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
