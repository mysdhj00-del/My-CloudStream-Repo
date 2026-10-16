package com.wecimamobile

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * WeCima provider for CloudStream 3 — a FRESH, single-domain build on
 * https://wecima.mobile, written 2026-10-16 at Mohamed's order after
 * the three-domain WeCima provider accumulated too many patches.
 *
 * Everything is traced live from the site itself (2026-10-16):
 * - Custom (non-WordPress) theme; no Cloudflare wall for plain fetches.
 *   The catalog UNIFIES Arabic series/movies and foreign subtitled
 *   content. Cards are a.show-card anchors (real img poster + p.title).
 * - Posts: /episode/{slug} and /film/{slug} with RAW Arabic slugs.
 *   Categories: /category/{arabic-slug}; home + categories paginate
 *   ?page={n}; search is /search?s={urlencoded q}.
 * - An episode page links its series' sibling episodes directly; the
 *   same-series set shares the slug stem (text before -الحلقة).
 * - Playback protocol: the post links /watch/{same slug}; the watch
 *   page carries `const servers = [...]` (12-14 standard hosts:
 *   earnvids, fastved, vinovo, m1xdrop, doodstream, voe, vidaraa,
 *   uqload, byse, vids, savefiles, vikingfile, ok, streamtape...),
 *   a watchResolveKey and a csrfToken, and sets session cookies.
 *   POST /watch/resolve {key, index} with the session cookies and the
 *   X-CSRF-TOKEN header answers {ok, url:"/m/{hash}"}; the fragment
 *   302 / meta-refreshes to the real host embed.
 *
 * Design rules for this build (lessons of the previous provider):
 * - ONE domain, one chain. No fallbacks to other domains, no twin
 *   redirects, no background prefetch, no parallel resolve storms,
 *   no response caches, no pacing gates — plain sequential requests
 *   exactly like the chain Mohamed confirmed working ("اشتغل",
 *   2026-10-16 12:09).
 * - EVERY server on the watch page is resolved and offered (never
 *   stop at the first one or two — Mohamed's standing rule).
 * - Browser headers on every request from v1.
 * - Any URL placed in a header is percent-encoded first: the site's
 *   slugs are raw Arabic and okhttp throws IllegalArgumentException
 *   on non-ASCII header values (the v12 root-cause fix, kept here).
 * - Every chain ends at an extractor-known host or a direct file:
 *   extractor -> phone-side scan (raw + packed-JS unpack) -> relay
 *   resolver. A DEBUG readout is emitted when nothing playable is
 *   found, so a failure names its own stage.
 */
class WeCimaMobileProvider : MainAPI() {
    override var mainUrl = "https://wecima.mobile"
    override var name = "WeCima"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "ar"
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

    // Sections mirror the site's own category menu.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "المضاف حديثا",
        "$mainUrl/category/افلام-اجنبي" to "أفلام أجنبية",
        "$mainUrl/category/افلام-عربي" to "أفلام عربية",
        "$mainUrl/category/افلام-هندي" to "أفلام هندية",
        "$mainUrl/category/افلام-تركية" to "أفلام تركية",
        "$mainUrl/category/افلام-اسيوية" to "أفلام آسيوية",
        "$mainUrl/category/افلام-انمي" to "أفلام أنمي",
        "$mainUrl/category/مسلسلات-عربي" to "مسلسلات عربية",
        "$mainUrl/category/مسلسلات-اجنبي" to "مسلسلات أجنبية",
        "$mainUrl/category/مسلسلات-تركية" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-اسيوية" to "مسلسلات آسيوية",
        "$mainUrl/category/مسلسلات-هندية" to "مسلسلات هندية",
        "$mainUrl/category/مسلسلات-مدبلجة" to "مسلسلات مدبلجة",
        "$mainUrl/category/مسلسلات-انمي" to "مسلسلات أنمي",
        "$mainUrl/category/برامج-تلفزيونية" to "برامج تلفزيونية",
        "$mainUrl/category/عروض-مصارعة" to "عروض مصارعة",
        "$mainUrl/category/مسلسلات-رمضان-2026" to "مسلسلات رمضان 2026",
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

    /**
     * Percent-encodes any non-ASCII characters of a URL string. The
     * site's anchors carry RAW Arabic slugs; okhttp percent-encodes a
     * URL in the request line by itself, but a raw-Arabic string used
     * as a HEADER value (referer) throws IllegalArgumentException
     * before any request is sent. Every header-bound URL goes through
     * this first.
     */
    private fun pctEncodeNonAscii(url: String): String {
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder()
        for (ch in url) {
            if (ch.code < 128) sb.append(ch)
            else sb.append(java.net.URLEncoder.encode(ch.toString(), "UTF-8"))
        }
        return sb.toString()
    }

    // ---------- cards ----------

    private fun Element.toMobileCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (!href.contains("/episode/") && !href.contains("/film/")) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("src"))?.takeIf { it.startsWith("http") }
            ?: fixUrlNull(img?.attr("data-src"))
        val title = cleanTitle(
            selectFirst("p.title")?.text() ?: img?.attr("alt") ?: attr("title")
        )
        if (title.isBlank()) return null
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val anime = isAnime(decode(href))
        return when {
            href.contains("/film/") && anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
            anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Document.parseMobileCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("a.show-card").forEach { a ->
            val card = runCatching { a.toMobileCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(card.url, card)
        }
        return seen.values.toList()
    }

    // ---------- browse / search ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The site paginates home + categories with ?page={n}.
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else "$base?page=$page"
        val items = app.get(url, headers = browserHeaders).document.parseMobileCards()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl + "/search?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        return app.get(url, headers = browserHeaders).document.parseMobileCards()
    }

    // ---------- title page ----------

    private fun mobileSeriesTitle(decodedSlug: String): String {
        val stem = decodedSlug.substringBefore("-الحلقة").substringBefore("-الموسم")
        return stem.removePrefix("مسلسل-").removePrefix("انمي-")
            .replace('-', ' ').trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
        val rawTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.title()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) throw ErrorLoadingException("No title")
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.trim()?.ifBlank { null }
        val anime = isAnime(decode(url))

        return if (url.contains("/episode/")) {
            // The episode page links its series' other episodes; the
            // same-series set shares this slug's stem (verified live:
            // an ep3 page links eps 1-4 of itself, alongside other
            // shows' latest episodes, which the stem filter drops).
            val mySlug = url.trimEnd('/').substringAfter("/episode/")
            val stem = decode(mySlug).substringBefore("-الحلقة").substringBefore("-الموسم")
            val byKey = LinkedHashMap<String, Episode>()
            fun addEpisode(href: String) {
                val dec = decode(href)
                val epSlug = href.trimEnd('/').substringAfter("/episode/")
                if (!decode(epSlug).startsWith(stem)) return
                val num = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(dec)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val ep = newEpisode(href) {
                    this.name = num?.let { "الحلقة $it" } ?: cleanTitle(dec.replace('-', ' '))
                    this.season = seasonOf(dec)
                    this.episode = num
                }
                byKey.putIfAbsent(href, ep)
            }
            document.select("a[href*=\"/episode/\"]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                if (href.startsWith(mainUrl)) addEpisode(href)
            }
            addEpisode(url)
            val episodes = byKey.values.sortedWith(
                compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE })
            )
            newTvSeriesLoadResponse(
                mobileSeriesTitle(decode(mySlug)).ifBlank { title }, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                episodes
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

    // ---------- playback ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val diag = mutableListOf("play")
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { emitted++; callback(it) }
        val found = runCatching { loadLinksMobile(data, diag, subtitleCallback, counting) }
            .getOrDefault(false)
        if (!found) emitDebug(diag, callback)
        return true
    }

    /**
     * The watch protocol, sequential end to end: watch page (session
     * cookies) -> one resolve POST per server -> fragment -> real
     * embed -> extractors -> scan -> relay. Mirrors exactly the chain
     * verified live from the site and confirmed on-device.
     */
    private suspend fun loadLinksMobile(
        data: String,
        diag: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Header-bound URLs are percent-encoded first (raw Arabic
        // slugs throw IllegalArgumentException as header values).
        val safeData = pctEncodeNonAscii(data)
        diag += "enc=1"
        val watchUrl = safeData.replace("/episode/", "/watch/").replace("/film/", "/watch/")

        // The host can be slow; retry the watch fetch with backoff
        // and record the exception class so a failure names itself.
        var watchErr: String? = null
        var res = runCatching { app.get(watchUrl, headers = browserHeaders, referer = safeData) }
            .onFailure { watchErr = it.javaClass.simpleName }.getOrNull()
        for (attempt in 2..3) {
            if (res != null) break
            Thread.sleep(if (attempt == 2) 1500L else 4000L)
            res = runCatching { app.get(watchUrl, headers = browserHeaders, referer = safeData) }
                .onFailure { watchErr = it.javaClass.simpleName }.getOrNull()
            if (res != null) watchErr = null
        }
        diag += "watch=${res?.code ?: "err"}"
        if (watchErr != null) diag += "werr=$watchErr"
        if (res == null) return false

        val cookies = runCatching { res.cookies }.getOrNull().orEmpty()
        val text = res.text
        val key = Regex("""watchResolveKey\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        val csrf = Regex("""csrfToken\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        val serversJson = Regex("""const servers = (\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        val serverNames = mutableListOf<String>()
        if (serversJson != null) {
            val arr = runCatching { org.json.JSONArray(serversJson) }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    serverNames += arr.optJSONObject(i)?.optString("name") ?: "server$i"
                }
            }
        }
        diag += "servers=${serverNames.size}"
        if (key == null || csrf == null || serverNames.isEmpty()) return false

        // Resolve EVERY server, in the site's order, one at a time.
        val embeds = mutableListOf<String>()
        for (i in serverNames.indices) {
            val embed = resolveOneMobile(i, key, csrf, cookies, watchUrl)
            if (embed != null && embed !in embeds) embeds += embed
            if (i < serverNames.size - 1) Thread.sleep(250)
        }
        diag += "resolved=${embeds.size}"
        if (embeds.isEmpty()) return false

        // Stage 1: the app's own extractors.
        val unresolved = mutableListOf<String>()
        var extracted = 0
        for (embed in embeds) {
            val ok = runCatching {
                loadExtractor(embed, watchUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) extracted++ else unresolved += embed
        }
        diag += "embeds=$extracted"

        // Stage 2: dig the direct file out of a rejected embed page.
        val stillUnresolved = mutableListOf<String>()
        var scanned = 0
        for (embed in unresolved) {
            if (runCatching { scanEmbed(embed, data, callback) }.getOrDefault(false)) {
                scanned++
            } else {
                stillUnresolved += embed
            }
        }
        diag += "scan=$scanned"

        // Stage 3: the relay's generic resolver dig (first 3 only).
        var relayed = 0
        for (embed in stillUnresolved.take(3)) {
            relayed += runCatching { resolveViaRelay(embed, data, callback) }.getOrDefault(0)
        }
        diag += "relay=$relayed"

        return extracted > 0 || scanned > 0 || relayed > 0
    }

    /** One server: POST /watch/resolve, then follow its fragment to
     * the real embed URL. The resolve POST is retried once. */
    private suspend fun resolveOneMobile(
        index: Int,
        key: String,
        csrf: String,
        cookies: Map<String, String>,
        watchUrl: String
    ): String? {
        val body = JSONObject().put("key", key).put("index", index).toString()
        val headers = browserHeaders + mapOf(
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json"
        )
        var fragPath: String? = null
        for (attempt in 1..2) {
            val rText = runCatching {
                app.post(
                    "$mainUrl/watch/resolve",
                    headers = headers,
                    cookies = cookies,
                    referer = watchUrl,
                    requestBody = body.toRequestBody("application/json".toMediaType())
                ).text
            }.getOrNull()
            fragPath = rText?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optBoolean("ok") }?.optString("url")?.takeIf { it.isNotBlank() }
            if (fragPath != null) break
            Thread.sleep(700)
        }
        val frag = fragPath ?: return null
        val fragUrl = if (frag.startsWith("http")) frag else mainUrl + frag
        val fres = runCatching {
            app.get(fragUrl, headers = browserHeaders, cookies = cookies, referer = watchUrl)
        }.getOrNull() ?: return null
        val finalUrl = fres.url
        if (finalUrl.startsWith("http") && !finalUrl.contains("wecima.mobile")) {
            return finalUrl
        }
        val t = runCatching { fres.text }.getOrNull() ?: return null
        return Regex("""url='([^']+)'""").find(t)?.groupValues?.get(1)
            ?: Regex("""<a href="([^"]+)"""").find(t)?.groupValues?.get(1)
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

    /** Stage 2 (phone-side): dig the direct file out of a rejected embed page. */
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

    // Dean Edwards packer blocks: eval(function(p,a,c,k,e,d){...}('payload',
    // radix, count, 'k1|k2|...'.split('|'))). Several embed hosts hide
    // their player config this way; unpacked, it carries a plain
    // file:"..." URL. Final fallback of the scan above.
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
