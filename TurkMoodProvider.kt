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
 */
class TurkMoodProvider : MainAPI() {
    override var mainUrl = "https://turkmoodapi.xyz"
    override var name = "TurkMood"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val api = "$mainUrl/wp-json/faselhd/v1"

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
        val text = app.get(url).text
        val (items, hasNext) = parseList(text, request.data)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val json = runCatching { JSONObject(app.get(url).text) }.getOrNull()
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
        val json = runCatching { JSONObject(app.get(url).text) }.getOrNull()
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
        val diag = mutableListOf<String>()
        val detailUrl: String
        val isEpisode = data.startsWith("episode:")
        if (isEpisode) {
            val epId = data.removePrefix("episode:")
            detailUrl = "$api/episodes/$epId"
        } else {
            detailUrl = data
        }
        val res = runCatching { app.get(detailUrl) }.getOrNull()
        diag += "page=${res?.code ?: "err"}"
        if (res == null) {
            emitDebug(diag, callback)
            return true
        }
        val json = runCatching { JSONObject(res.text) }.getOrNull()
        if (json == null) {
            diag += "json=err"
            emitDebug(diag, callback)
            return true
        }
        // Movies: servers live under item.servers; episodes: under episode.servers.
        val holder = when {
            isEpisode -> json.optJSONObject("episode")
            else -> json.optJSONObject("item")
        }
        if (holder == null) {
            diag += "holder=none"
            emitDebug(diag, callback)
            return true
        }
        val servers = holder.optJSONArray("servers")
        val downloads = holder.optJSONArray("download_links")
        diag += "servers=${servers?.length() ?: 0}"
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
        val seen = HashSet<String>()
        var found = false
        suspend fun resolve(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("url", "").trim()
                if (link.isBlank() || !link.startsWith("http")) continue
                if (!seen.add(link)) continue
                if (loadExtractor(link, detailUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        resolve(servers)
        resolve(downloads)
        if (!found) {
            diag += "resolved=0"
            emitDebug(diag, callback)
            return true
        }
        return true
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
