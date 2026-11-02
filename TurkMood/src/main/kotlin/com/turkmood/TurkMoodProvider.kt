package com.turkmood

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

/**
 * TurkMood provider for CloudStream 3.
 *
 * Built against the live structure of the TurkMood app's open API
 * (https://turkmoodapi.xyz/wp-json/faselhd/v1/), verified 2026-11-02:
 * - Lists: /series, /movies, /episodes/latest and the category variants
 *   (series-turkish-translated, series-turkish-dubbed,
 *   movies-turkish-translated, movies-turkish-dubbed, ...), all returning
 *   {success, total, pages, page, per_page, items[]} with page/per_page
 *   query args (default 20 per page).
 * - Search: /search?q= returns {success, query, total, items[]} across
 *   series and movies (item_type distinguishes them).
 * - Detail: /series/{id} -> {item: {seasons: [{episodes: [...]}}]},
 *   /movies/{id} -> {item: {servers: [{name, url}], download_links: [...]}}.
 * - Episode: /episodes/{id} -> {episode: {servers: [...], download_links:
 *   [...], subtitles: [...]}, series: {...}, seasons: [...]}.
 * - Playback servers are extractor-known hosts (StreamWish, Uqload,
 *   VidHide/EarnVids, MiraVD, Mwdy, StreamRuby, ...), resolved with
 *   loadExtractor. No login, no tokens, no session on any call.
 *
 * v2 (panel consensus, 2026-11-02): ranked server resolution (reachable
 * hosts first, Cloudflare-walled MiraVD/Mwdy last), every server attempted
 * with a per-server outcome, structured DEBUG diagnostics that distinguish
 * API failure vs extractor failure vs Cloudflare block, dual-host API
 * fallback (turkmoodapi.xyz -> moodkapi.xyz), and a browser-like UA on
 * API calls. The DEBUG source is diagnostic only: its name starts with
 * the failure class (API_FAILURE / NO_PLAYABLE_SOURCE), never a success.
 * The FaselHD player_token chain (iframe -> video_player?player_token=
 * -> scdns.io m3u8, WebView-based in the native app) is NOT in v2: the
 * API exposes no FaselHD page URL per title, so that chain is still
 * research, not a shippable plain-HTTP path.
 */
class TurkMoodProvider : MainAPI() {
    override var mainUrl = "https://turkmoodapi.xyz"
    override var name = "TurkMood"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val api = "$mainUrl/wp-json/faselhd/v1"

    // v2: browser-like headers on API calls (the API host is Cloudflare-
    // fronted and filters some clients); harmless for the calls that
    // already work, and one candidate explanation for phone-side WAF
    // differences on the episode route.
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        "Accept" to "application/json"
    )

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/")

    private fun altHost(url: String): String =
        url.replace("https://turkmoodapi.xyz", "https://moodkapi.xyz")

    // v2 server ranking: reachable hosts first, Cloudflare-walled
    // MiraVD/Mwdy last (both return 403 "Just a moment..." to plain HTTP).
    private fun serverRank(url: String): Int {
        val h = url.lowercase()
        return when {
            "streamwish.to" in h || "streamhg" in h -> 0
            "uqload" in h -> 1
            "vidhide" in h || "earnvids" in h -> 2
            "stmruby" in h || "streamruby" in h -> 3
            "miravd" in h || "mwdy" in h -> 5
            else -> 4
        }
    }

    private fun bodyClass(text: String): String = when {
        text.contains("Just a moment") || text.contains("cf-challenge") || text.contains("challenges.cloudflare.com") -> "CF_CHALLENGE"
        text.trimStart().startsWith("<") -> "HTML"
        text.trimStart().startsWith("{") || text.trimStart().startsWith("[") -> "JSON"
        else -> "OTHER"
    }

    override val mainPage = mainPageOf(
        "$api/episodes/latest" to "أحدث الحلقات",
        "$api/series" to "مسلسلات",
        "$api/movies" to "أفلام",
        "$api/series-turkish-translated" to "مسلسلات تركية مترجمة",
        "$api/series-turkish-dubbed" to "مسلسلات تركية مدبلجة",
        "$api/movies-turkish-translated" to "أفلام تركية مترجمة",
        "$api/movies-turkish-dubbed" to "أفلام تركية مدبلجة",
    )

    // ---------- JSON helpers ----------

    private fun JSONObject.optStr(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun JSONObject.optYear(): Int? {
        val raw = opt("year") ?: return null
        val n = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: return null
            else -> return null
        }
        return n.takeIf { it > 1900 }
    }

    private fun JSONObject.poster(): String? =
        optStr("poster", "poster_local", "tmdb_poster", "poster_url", "cover")

    private fun JSONObject.toCard(rowUrl: String): SearchResponse? {
        val id = optInt("id", -1)
        if (id < 0) return null
        val title = optStr("title") ?: return null
        val poster = poster()
        val year = optYear()
        return when {
            rowUrl.contains("/episodes/latest") -> {
                // Latest-episode items link to their series page; the
                // series page lists every episode for playback.
                val seriesId = optInt("series_id", -1)
                if (seriesId < 0) return null
                val seriesTitle = optStr("series_title") ?: title
                newTvSeriesSearchResponse(seriesTitle, "$api/series/$seriesId", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            rowUrl.contains("/series") -> {
                newTvSeriesSearchResponse(title, "$api/series/$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(title, "$api/movies/$id", TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    private fun parseList(text: String, rowUrl: String): Pair<List<SearchResponse>, Boolean> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList<SearchResponse>() to false
        val items = json.optJSONArray("items") ?: return emptyList<SearchResponse>() to false
        val seen = LinkedHashMap<String, SearchResponse>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val card = runCatching { obj.toCard(rowUrl) }.getOrNull() ?: continue
            seen.putIfAbsent(card.url, card)
        }
        val page = json.optInt("page", 1)
        val pages = json.optInt("pages", 1)
        val hasNext = if (pages > 0) page < pages else seen.isNotEmpty()
        return seen.values.toList() to hasNext
    }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val sep = if (base.contains("?")) "&" else "?"
        val url = "$base${sep}page=$page"
        val text = app.get(url, headers = apiHeaders).text
        val (items, hasNext) = parseList(text, request.data)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val json = runCatching { JSONObject(app.get(url, headers = apiHeaders).text) }.getOrNull()
            ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = obj.optInt("id", -1)
            if (id < 0) continue
            val title = obj.optStr("title") ?: continue
            val poster = obj.poster()
            val year = obj.optYear()
            val itemType = obj.optString("item_type", "")
            val isSeries = when {
                itemType == "series" -> true
                itemType == "movie" -> false
                title.startsWith("مسلسل") -> true
                else -> false
            }
            if (isSeries) {
                out += newTvSeriesSearchResponse(title, "$api/series/$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                out += newMovieSearchResponse(title, "$api/movies/$id", TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val json = runCatching { JSONObject(app.get(url, headers = apiHeaders).text) }.getOrNull()
            ?: throw ErrorLoadingException("No response")
        val item = json.optJSONObject("item") ?: throw ErrorLoadingException("No item")
        val title = item.optStr("title") ?: throw ErrorLoadingException("No title")
        val poster = item.poster()
        val plot = item.optStr("description")
        val year = item.optYear()
        return if (url.contains("/series/")) {
            val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
            val seasons = item.optJSONArray("seasons")
            if (seasons != null) {
                for (s in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(s) ?: continue
                    val seasonNum = season.optInt("season_number", 1)
                    val eps = season.optJSONArray("episodes") ?: continue
                    for (e in 0 until eps.length()) {
                        val ep = eps.optJSONObject(e) ?: continue
                        val epId = ep.optInt("id", -1)
                        if (epId < 0) continue
                        val epTitle = ep.optStr("title") ?: "حلقة ${ep.optInt("episode_number", e + 1)}"
                        episodes += newEpisode("episode:$epId") {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = ep.optInt("episode_number", e + 1)
                        }
                    }
                }
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes")
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v2: dual-host API fallback + ranked server resolution +
        // structured diagnostics (API vs extractor vs Cloudflare block).
        val diag = mutableListOf<String>()
        val isEpisode = data.startsWith("episode:")
        val primaryUrl = if (isEpisode) "$api/episodes/${data.removePrefix("episode:")}" else data
        var detailUrl = primaryUrl
        var bodyText: String? = null
        for (u in listOf(primaryUrl, altHost(primaryUrl)).distinct()) {
            val r = runCatching { app.get(u, headers = apiHeaders) }.getOrNull()
            if (r == null) {
                diag += "api=${hostOf(u)}:ERR"
                continue
            }
            val t = runCatching { r.text }.getOrNull() ?: ""
            val cls = bodyClass(t)
            if (r.code == 200 && cls == "JSON") {
                bodyText = t
                detailUrl = u
                diag += "api=${hostOf(u)}:200/json-ok"
                break
            }
            diag += "api=${hostOf(u)}:${r.code}/$cls"
        }
        if (bodyText == null) {
            emitDebug(listOf("API_FAILURE") + diag, callback)
            return true
        }
        val json = runCatching { JSONObject(bodyText) }.getOrNull()
        if (json == null) {
            emitDebug(listOf("API_FAILURE", "json=err") + diag, callback)
            return true
        }
        // Movies: servers live under item.servers; episodes: under episode.servers.
        val holder = when {
            isEpisode -> json.optJSONObject("episode")
            else -> json.optJSONObject("item")
        }
        if (holder == null) {
            emitDebug(listOf("API_FAILURE", "holder=none") + diag, callback)
            return true
        }
        // Subtitles when the API provides them (shape: [{url, language}]).
        val subs = holder.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val surl = s.optString("url", "").trim()
                if (surl.isBlank()) continue
                val slang = s.optString("language", "").ifBlank {
                    s.optString("lang", "").ifBlank { s.optString("label", "und") }
                }
                subtitleCallback.invoke(SubtitleFile(slang, surl))
            }
        }
        // Merge servers + download links, ranked: reachable hosts first,
        // download links next, MiraVD/Mwdy last (Cloudflare-walled).
        val ranked = ArrayList<Triple<Int, Int, Pair<String, String>>>()
        var order = 0
        fun collect(arr: JSONArray?, isDownload: Boolean) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("url", "").trim()
                if (link.isBlank() || !link.startsWith("http")) continue
                val nm = o.optString("name", "").ifBlank {
                    o.optString("quality", "").ifBlank { if (isDownload) "dl" else "srv" }
                }
                val rank = if (isDownload && serverRank(link) < 5) 4 else serverRank(link)
                ranked += Triple(rank, order++, nm to link)
            }
        }
        collect(holder.optJSONArray("servers"), false)
        collect(holder.optJSONArray("download_links"), true)
        ranked.sortWith(compareBy({ it.first }, { it.second }))
        val seen = HashSet<String>()
        val outcomes = ArrayList<String>()
        var found = false
        for ((_, _, entry) in ranked) {
            val (nm, link) = entry
            if (!seen.add(link)) continue
            val ok = runCatching { loadExtractor(link, detailUrl, subtitleCallback, callback) }.getOrNull() ?: false
            outcomes += "$nm:${if (ok) "OK" else "FAIL"}"
            if (ok) found = true
        }
        if (!found) {
            emitDebug(listOf("NO_PLAYABLE_SOURCE") + diag + listOf("tried=${outcomes.size}") + outcomes, callback)
            return true
        }
        return true
    }

    /** Emits the collected stage markers as one diagnostic-only source. */
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
