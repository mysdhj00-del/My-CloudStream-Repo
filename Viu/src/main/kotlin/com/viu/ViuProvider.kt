package com.viu

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
import com.lagradost.cloudstream3.fixUrlNull
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Viu (Egypt region) provider for CloudStream 3 — v1, guest scope.
 *
 * Viu hard geo-fences its catalog: this provider only works from an
 * Egyptian network (or the user's VPN exit in Egypt, which is how the
 * user already watches Viu). It cannot be exercised from datacenter IPs
 * at all — www pages redirect to /ott/no-service/ and the API gateway is
 * unreachable — so v1 is deliberately DEBUG-first: every playback step
 * reports a marker, and the design is iterated from the user's on-device
 * readouts.
 *
 * Flow (the service's documented OTT guest flow):
 * 1. Guest token: POST api-gateway-global.viu.com/api/auth/token with a
 *    JSON body {countryCode: EG, platform: browser, platformFlagLabel:
 *    web, language: en, uuid, carrierId: 0} -> {"token": ...}.
 * 2. Detail: GET www.viu.com/ott/eg/index.php?r=vod/ajax-detail&
 *    platform_flag_label=web&product_id={id} -> data.current_product
 *    (ccs_product_id, synopsis, description, cover_image_url, subtitle[])
 *    and data.series.product[] (the episode list).
 * 3. Playback: GET api-gateway-global.viu.com/api/playback/distribute?
 *    ccs_product_id={ccs}&language_flag_id=3 with the Bearer token ->
 *    stream.url = {s720p: mp4-url, ...} quality map.
 * Premium/VIP titles require the user's own account and are out of
 * scope: no preview-limit tricks, guest/free content only.
 * Browse/search in v1 scrape the OTT pages for /vod/{product_id} links
 * (the listing ajax routes are not public); empty sections are a known
 * v1 possibility pending field readouts.
 */
class ViuProvider : MainAPI() {
    override var mainUrl = "https://www.viu.com"
    override var name = "Viu"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val cc = "eg"
    private val apiGateway = "https://api-gateway-global.viu.com"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ott/$cc/index.php" to "Viu مصر",
        "$mainUrl/ott/$cc/ar/" to "الأحدث",
    )

    // ---------- helpers ----------

    private fun productIdOf(url: String): String? =
        Regex("""vod/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""product_id=(\d+)""").find(url)?.groupValues?.get(1)
            ?: url.trim().takeIf { it.all { c -> c.isDigit() } && it.isNotEmpty() }

    private fun itemUrl(productId: String): String = "$mainUrl/ott/$cc/ar/vod/$productId/"

    private var cachedToken: String? = null

    /** The guest Bearer token (cached per provider instance). */
    private suspend fun guestToken(): String? {
        cachedToken?.let { return it }
        val rand = (1..10).map { ('0'..'9').random() }.joinToString("")
        val uuid = java.util.UUID.randomUUID().toString()
        val json = """{"countryCode":"EG","platform":"browser","platformFlagLabel":"web","language":"en","uuid":"$uuid","carrierId":"0"}"""
        val res = runCatching {
            app.post(
                "$apiGateway/api/auth/token?v=${rand}000",
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        }.getOrNull() ?: return null
        val body = runCatching { JSONObject(res.text) }.getOrNull() ?: return null
        val token = body.optString("token").ifBlank {
            body.optJSONObject("data")?.optString("token") ?: ""
        }
        return token.ifBlank { null }?.also { cachedToken = it }
    }

    /** The ajax-detail JSON for a product, or null at any failure step. */
    private suspend fun fetchDetail(productId: String): JSONObject? {
        val url = "$mainUrl/ott/$cc/index.php?r=vod/ajax-detail&platform_flag_label=web&product_id=$productId"
        val res = runCatching { app.get(url, headers = browserHeaders) }.getOrNull() ?: return null
        val root = runCatching { JSONObject(res.text) }.getOrNull() ?: return null
        return root.optJSONObject("data")
    }

    private fun scrapeCards(html: String, base: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html, base)
        val seen = LinkedHashMap<String, SearchResponse>()
        doc.select("a[href*=\"/vod/\"]").forEach { a ->
            val pid = productIdOf(a.attr("href")) ?: return@forEach
            if (seen.containsKey(pid)) return@forEach
            val img = a.selectFirst("img")
            val title = (img?.attr("alt") ?: "").ifBlank { a.text() }.trim()
                .ifBlank { a.attr("title") }
            if (title.isBlank()) return@forEach
            val poster = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))
            val isSeries = a.attr("href").contains("series") ||
                title.contains("مسلسل") || title.contains("الحلقة")
            seen[pid] = if (isSeries) {
                newTvSeriesSearchResponse(title, itemUrl(pid), TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, itemUrl(pid), TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
        return seen.values.toList()
    }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = runCatching { app.get(request.data, headers = browserHeaders) }.getOrNull()
        val items = res?.let { scrapeCards(it.text, request.data) } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val ajaxUrl = "$mainUrl/ott/$cc/index.php?r=vod/ajax-search&keyword=$enc"
        val res = runCatching { app.get(ajaxUrl, headers = browserHeaders) }.getOrNull()
        val fromAjax = res?.let { scrapeCards(it.text, ajaxUrl) } ?: emptyList()
        if (fromAjax.isNotEmpty()) return fromAjax
        // Fallback: filter the home scrape by the query words.
        val home = runCatching {
            app.get("$mainUrl/ott/$cc/index.php", headers = browserHeaders)
        }.getOrNull() ?: return emptyList()
        val words = query.split(' ').filter { it.isNotBlank() }
        return scrapeCards(home.text, home.url).filter { item ->
            words.any { w -> item.name.contains(w, ignoreCase = true) }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val pid = productIdOf(url) ?: throw ErrorLoadingException("No product id")
        val data = fetchDetail(pid) ?: throw ErrorLoadingException("No detail")
        val current = data.optJSONObject("current_product")
            ?: throw ErrorLoadingException("No product")
        val title = current.optString("synopsis").ifBlank { "Viu $pid" }
        val poster = fixUrlNull(current.optString("cover_image_url"))
        val plot = current.optString("description").ifBlank { null }

        val series = data.optJSONObject("series")
        val products = series?.optJSONArray("product")
        val seriesName = series?.optString("name")?.ifBlank { null }

        return if (products != null && products.length() >= 2) {
            val episodes = mutableListOf<Episode>()
            for (i in 0 until products.length()) {
                val entry = products.optJSONObject(i) ?: continue
                val epPid = entry.optString("product_id")
                if (epPid.isBlank()) continue
                val epNum = entry.optString("number").toIntOrNull()
                episodes += newEpisode(itemUrl(epPid)) {
                    this.name = epNum?.let { "الحلقة $it" }
                        ?: entry.optString("synopsis").ifBlank { "حلقة" }
                    this.season = 1
                    this.episode = epNum
                }
            }
            episodes.sortBy { it.episode ?: 0 }
            newTvSeriesLoadResponse(seriesName ?: title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, itemUrl(pid)) {
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
        val diag = mutableListOf<String>()
        val pid = productIdOf(data)
        if (pid == null) {
            diag += "pid:missing"
            emitDebug(diag, callback)
            return true
        }
        val detail = fetchDetail(pid)
        diag += "detail=${if (detail != null) "ok" else "fail"}"
        val current = detail?.optJSONObject("current_product")
        val ccs = current?.optString("ccs_product_id") ?: ""
        diag += "ccs=${if (ccs.isNotBlank()) "ok" else "missing"}"

        // Subtitles ride along with the detail payload.
        val subs = current?.optJSONArray("subtitle")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url")
                val subName = sub.optString("name").ifBlank { "Subtitle" }
                if (subUrl.isNotBlank()) {
                    runCatching { subtitleCallback(SubtitleFile(subName, subUrl)) }
                }
                val second = sub.optString("second_subtitle_url")
                if (second.isNotBlank()) {
                    runCatching { subtitleCallback(SubtitleFile(subName, second)) }
                }
            }
        }

        val token = guestToken()
        diag += "token=${if (token != null) "ok" else "fail"}"
        if (token == null || ccs.isBlank()) {
            emitDebug(diag, callback)
            return true
        }

        val distUrl = "$apiGateway/api/playback/distribute?ccs_product_id=$ccs&language_flag_id=3"
        val res = runCatching {
            app.get(
                distUrl,
                headers = browserHeaders + mapOf(
                    "Authorization" to "Bearer $token",
                    "Referer" to data,
                    "Origin" to mainUrl
                )
            )
        }.getOrNull()
        diag += "dist=${res?.code ?: "err"}"
        val root = res?.let { runCatching { JSONObject(it.text) }.getOrNull() }
        val stream = root?.optJSONObject("stream")
            ?: root?.optJSONObject("data")?.optJSONObject("stream")
        val urls = stream?.optJSONObject("url")
        if (urls == null) {
            emitDebug(diag, callback)
            return true
        }
        var emitted = 0
        val keys = urls.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val streamUrl = urls.optString(key)
            if (streamUrl.isBlank()) continue
            val height = Regex("""s(\d+)p""").find(key)?.groupValues?.get(1)?.toIntOrNull()
            callback(
                newExtractorLink(name, "$name $key", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = data
                    this.quality = when (height) {
                        null -> Qualities.Unknown.value
                        in 0..359 -> Qualities.P360.value
                        in 360..479 -> Qualities.P480.value
                        in 480..719 -> Qualities.P720.value
                        else -> Qualities.P1080.value
                    }
                }
            )
            emitted++
        }
        diag += "streams=$emitted"
        if (emitted == 0) {
            emitDebug(diag, callback)
            return true
        }
        return true
    }

    /** Emits the collected step markers as one playable debug source. */
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
