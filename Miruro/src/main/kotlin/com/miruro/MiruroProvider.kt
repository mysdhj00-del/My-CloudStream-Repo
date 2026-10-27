package com.miruro

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.zip.GZIPInputStream

/**
 * Miruro (miruro.cx) — anime catalog, ALL sections.
 *
 * Data source (verified from the site's own client code
 * 2026-10-28): the miruro catalog API on the site origin.
 * Every response is XOR-obfuscated with the key
 * "miruro/catalog" and then gzipped — the provider reverses
 * exactly that (same as the site's own decoder).
 *
 * - Sections: /v1/anime/trending (limit + season +
 *   season_year), /v1/anime (sort=-popularity /
 *   -average_score / -started_on, format=MOVIE|TV),
 *   /v1/schedule, search via /v1/anime?search=.
 * - Detail: /v1/anime/{id} (same object the site embeds
 *   as __SSR_DATA__ on info/watch pages: id, title{english,
 *   romaji, native}, format, episode_counts{sub, dub, raw},
 *   description, genres, cover_url, banner_url,
 *   average_score, external_ids{anilist, mal, ...}).
 * - Episodes: /v1/anime/{id}/episodes (fallback: counts).
 * - Playback: /v1/anime/{id}/episodes/{ep}/play returns
 *   the strm.cx session (tracks + streams per provider:
 *   icarus/sun, anikoto/bee, kickassanime/hop, animepahe/
 *   kiwi, aniwaves/knob). Stream URLs are extracted
 *   defensively (m3u8/mp4 strings anywhere in the session
 *   JSON + embed URLs via loadExtractor).
 *
 * Phone-vantage note: the /api/v1 endpoints return Cloudflare 403
 * from datacenter egress (sandbox + Render, verified
 * 2026-10-28) while the site's pages return 200 — the
 * provider runs on the phone, where the site works in
 * the browser, so API calls are expected to pass there.
 * If a row/playback comes back empty on-device with a
 * DEBUG line, that is the first thing to check.
 */
class MiruroProvider : MainAPI() {
    override var mainUrl = "https://www.miruro.cx"
    override var name = "Miruro"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiBase = "https://www.miruro.cx/api"
    private val xorKey = "miruro/catalog".toByteArray(Charsets.UTF_8)
    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrome/130.0.0.0 Safari/537.36"

    private fun seasonNow(): Pair<String, Int> {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        val season = when (month) {
            in 1..3 -> "WINTER"
            in 4..6 -> "SPRING"
            in 7..9 -> "SUMMER"
            else -> "FALL"
        }
        return Pair(season, year)
    }

    override val mainPage = mainPageOf(
        "trending" to "Trending This Season",
        "popular" to "Most Popular",
        "toprated" to "Top Rated",
        "newest" to "Newest",
        "movies" to "Movies",
        "series" to "TV Series",
        "schedule" to "Airing Schedule",
    )

    // ---------- transport: XOR + gunzip catalog API ----------

    private fun apiBytes(path: String): ByteArray? {
        return runCatching {
            val conn = URL(apiBase + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", browserUA)
            conn.setRequestProperty("Accept", "application/json, */*")
            conn.setRequestProperty("Referer", "https://www.miruro.cx/")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.readBytes()
        }.getOrNull()
    }

    /** Reverse the site's own obfuscation: XOR + gunzip. */
    private fun apiJson(path: String): String? {
        val raw = apiBytes(path) ?: return null
        return runCatching {
            val xored = ByteArray(raw.size) { i ->
                (raw[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
            }
            GZIPInputStream(xored.inputStream()).bufferedReader(Charsets.UTF_8).readText()
        }.getOrNull()
    }

    // ---------- catalog parsing (defensive: envelope unknown) ----------

    private fun titleOf(o: JSONObject): String {
        val t = o.optJSONObject("title")
        if (t != null) {
            for (k in listOf("english", "romaji", "native")) {
                val v = t.optString(k)
                if (v.isNotBlank()) return v
            }
        }
        val s = o.optString("title")
        if (s.isNotBlank() && !s.startsWith("{")) return s
        return o.optString("name")
    }

    private fun posterOf(o: JSONObject): String? {
        for (k in listOf("cover_url", "cover", "poster", "image")) {
            val v = o.optString(k)
            if (v.isNotBlank()) return v
        }
        val c = o.optJSONObject("coverImage")
        if (c != null) {
            val v = c.optString("large").ifBlank { c.optString("medium") }
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun itemToCard(o: JSONObject): SearchResponse? {
        val id = o.optString("id")
        if (id.isBlank()) return null
        val title = titleOf(o)
        if (title.isBlank()) return null
        val url = "$apiBase/v1/anime/$id"
        val isMovie = o.optString("format").equals("MOVIE", ignoreCase = true)
        val r = if (isMovie)
            newMovieSearchResponse(title, url, TvType.Movie)
        else
            newTvSeriesSearchResponse(title, url, TvType.TvSeries)
        posterOf(o)?.let { r.posterUrl = it }
        return r
    }

    /** Accepts: bare array, or object with data/items/results/anime. */
    private fun parseList(json: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        runCatching {
            val t = json.trim()
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { itemToCard(it)?.let { out += it } }
                }
                return out
            }
            val obj = JSONObject(t)
            for (key in listOf("data", "items", "results", "anime", "list")) {
                val arr = obj.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { itemToCard(it)?.let { out += it } }
                }
                if (out.isNotEmpty()) return out
            }
            // Single object = one title.
            itemToCard(obj)?.let { out += it }
        }
        return out
    }

    // ---------- main page + search ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (season, year) = seasonNow()
        val path = when (request.data) {
            "trending" -> "/v1/anime/trending?limit=24&season=$season&season_year=$year"
            "popular" -> "/v1/anime?sort=-popularity&limit=24"
            "toprated" -> "/v1/anime?sort=-average_score&limit=24"
            "newest" -> "/v1/anime?sort=-started_on&limit=24"
            "movies" -> "/v1/anime?format=MOVIE&sort=-popularity&limit=24"
            "series" -> "/v1/anime?format=TV&sort=-popularity&limit=24"
            "schedule" -> "/v1/schedule?limit=24"
            else -> ""
        }
        if (path.isBlank()) return newHomePageResponse(emptyList(), false)
        val json = apiJson(path) ?: return newHomePageResponse(emptyList(), false)
        return newHomePageResponse(
            listOf(HomePageList(request.name, parseList(json).take(24), isHorizontalImages = true)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = apiJson("/v1/anime?search=$q&sort=-popularity&limit=24")
            ?: return emptyList()
        return parseList(json).take(24)
    }

    // ---------- load ----------

    private fun subData(id: String, ep: Int, title: String): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return "mi|||$id|||$ep|||$enc"
    }

    override suspend fun load(url: String): LoadResponse? {
        val json = apiJson(url.removePrefix(apiBase)) ?: runCatching {
            // Card URLs are detail-API URLs; tolerate a full URL too.
            apiJson(URL(url).path.removePrefix("/api"))
        }.getOrNull() ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val detail = obj.optJSONObject("data") ?: obj
        val id = detail.optString("id")
        if (id.isBlank()) return null
        val title = titleOf(detail)
        if (title.isBlank()) return null
        val poster = posterOf(detail)
        val plot = detail.optString("description")
            .replace(Regex("<[^>]+>"), "").trim()
        val isMovie = detail.optString("format").equals("MOVIE", ignoreCase = true)
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, subData(id, 1, title)) {
                if (!poster.isNullOrBlank()) this.posterUrl = poster
                if (plot.isNotBlank()) this.plot = plot
            }
        }
        // Episodes: prefer the episodes endpoint, fall back to counts.
        var epNums: List<Int>? = null
        runCatching {
            val epJson = apiJson("/v1/anime/$id/episodes") ?: return@runCatching
            val nums = mutableListOf<Int>()
            val t = epJson.trim()
            val collect = { arr: JSONArray ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optInt("episode_number", -1)
                        .takeIf { it > 0 }
                        ?: o.optInt("number", -1).takeIf { it > 0 }
                        ?: o.optInt("episode", -1).takeIf { it > 0 }
                    if (n != null) nums += n
                }
            }
            if (t.startsWith("[")) collect(JSONArray(t)) else {
                val o = JSONObject(t)
                for (key in listOf("data", "items", "results", "episodes")) {
                    o.optJSONArray(key)?.let { collect(it) }
                }
            }
            if (nums.isNotEmpty()) epNums = nums.sorted()
        }
        if (epNums == null) {
            val counts = detail.optJSONObject("episode_counts")
            val n = counts?.optInt("sub", -1)?.takeIf { it > 0 }
                ?: counts?.optInt("raw", -1)?.takeIf { it > 0 }
                ?: detail.optInt("episode_count", -1).takeIf { it > 0 }
            if (n != null && n > 0) epNums = (1..n).toList()
        }
        val eps = (epNums ?: listOf(1)).map { n ->
            newEpisode(subData(id, n, title)) {
                this.name = "الحلقة $n"
                this.season = 1
                this.episode = n
                if (!poster.isNullOrBlank()) this.posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            if (!poster.isNullOrBlank()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
            }
            if (plot.isNotBlank()) this.plot = plot
        }
    }

    // ---------- playback ----------

    private fun collectUrls(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) collectUrls(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectUrls(node.opt(i), out)
            }
            is String -> {
                if (node.startsWith("http") && out.size < 40) out += node
            }
        }
    }

    private suspend fun emitDebug(diag: List<String>, callback: (ExtractorLink) -> Unit) {
        try {
            callback(
                newExtractorLink(
                    "Miruro",
                    "DEBUG " + diag.joinToString(" | "),
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var parts = data.split("|||")
        if (parts.size < 3) {
            val decoded = runCatching {
                java.net.URLDecoder.decode(data, "UTF-8")
            }.getOrNull()
            if (decoded != null) parts = decoded.split("|||")
        }
        if (parts.size < 3 || parts[0] != "mi") {
            emitDebug(listOf("Miruro", "unknown-data"), callback)
            return true
        }
        val id = parts[1]
        val ep = parts[2].toIntOrNull() ?: 1
        val title = runCatching {
            java.net.URLDecoder.decode(parts.getOrNull(3) ?: "", "UTF-8")
        }.getOrDefault("")
        val json = apiJson("/v1/anime/$id/episodes/$ep/play")
        if (json == null) {
            emitDebug(listOf("Miruro", "play-api-fail"), callback)
            return true
        }
        val urls = mutableListOf<String>()
        runCatching {
            val t = json.trim()
            if (t.startsWith("[")) collectUrls(JSONArray(t), urls)
            else collectUrls(JSONObject(t), urls)
        }
        val streams = urls.filter {
            it.contains(".m3u8") || it.contains(".mp4")
        }.distinct()
        val embeds = urls.filter {
            !it.contains(".m3u8") && !it.contains(".mp4") &&
                (it.contains("strm.cx") || it.contains("embed"))
        }.distinct()
        var emitted = 0
        val referer = "https://www.miruro.cx/watch/$id"
        for ((i, u) in streams.take(5).withIndex()) {
            val kind = if (u.contains(".m3u8")) "HLS" else "MP4"
            val type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8
                else ExtractorLinkType.VIDEO
            try {
                callback(
                    newExtractorLink("Miruro", "$kind ${i + 1}", u, type) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted++
            } catch (e: Exception) {
            }
        }
        if (emitted == 0) {
            for (e in embeds.take(2)) {
                runCatching {
                    loadExtractor(e, referer, subtitleCallback, callback)
                }
            }
        }
        // Subtitle files riding the session (vtt/srt), if any.
        runCatching {
            val subs = urls.filter {
                it.contains(".vtt") || it.contains(".srt")
            }.distinct()
            subs.firstOrNull()?.let {
                subtitleCallback(SubtitleFile("English", it))
            }
        }
        if (emitted == 0 && embeds.isEmpty()) {
            emitDebug(listOf("Miruro", "no-streams"), callback)
        }
        return true
    }
}
