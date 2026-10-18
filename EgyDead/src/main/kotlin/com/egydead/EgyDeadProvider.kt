package com.egydead

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
import org.json.JSONObject

/**
 * EgyDead provider for CloudStream 3 — v15, rebuilt on the operator's own
 * app domain egydead.ca (an MTDb v4 instance) after tv10.egydead.live raised
 * a full interactive Cloudflare challenge against every non-browser client
 * (measured 2026-10-17/18: Deno, Render, sandbox impersonation, headless
 * Chrome and GitHub Actions egress all answer 403; the .ca zone carries no
 * such challenge and answers plain requests).
 *
 * Chain verified live from the sandbox on 2026-10-18 before this build:
 * - Bootstrap: GET / sets the guest session cookies and embeds
 *   window.bootstrapData with a csrf_token. The JSON API answers 401 with
 *   no cookies AND with cookies alone; cookies + X-XSRF-TOKEN (the decoded
 *   XSRF cookie) + X-CSRF-TOKEN (bootstrap token) answer 200.
 * - Catalog/search: GET /api/v1/titles?type=movie|series&query=&orderBy=
 *   views|popularity&orderDirection=desc&page=&perPage= -> paginated JSON.
 * - Detail: GET /api/v1/titles/{id} -> title + seasons skeleton.
 * - Videos: GET /api/v1/videos?title_id={id}&perPage=100&page= -> every
 *   server row inline (name, src, episode_id, season_num, episode_num).
 *   Episodes are grouped client-side from these rows.
 * - Playback: a video's src is an embed page (egybestvid.com class) whose
 *   HTML carries a plain JW `file:` master.m3u8 — dug LOCALLY on the phone
 *   (stream tokens may be IP-bound; nothing is dug server-side), with
 *   CloudStream's built-in extractors as the fallback for other hosts.
 */
class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://egydead.ca"
    override var name = "EgyDead"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // A browser identity on every request (the provider's standing rule
    // since the v7/v8 CDN blocks): an ANDROID Chrome UA, matching the
    // phone + mobile-carrier reality of the app's traffic.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "type=movie" to "أحدث الأفلام",
        "type=series" to "أحدث المسلسلات",
        "type=movie&orderBy=views&orderDirection=desc" to "أفلام الأكثر مشاهدة",
        "type=series&orderBy=views&orderDirection=desc" to "مسلسلات الأكثر مشاهدة",
        "orderBy=popularity&orderDirection=desc" to "الأكثر شعبية"
    )

    // ---------- guest session ----------

    @Volatile private var sessionCookies: Map<String, String> = emptyMap()
    @Volatile private var csrfToken: String = ""
    @Volatile private var sessionAtMs: Long = 0

    // v16: the session is carried MANUALLY. The raw Set-Cookie name=value
    // pairs from the bootstrap response are replayed verbatim in a Cookie
    // header built by hand — the v15 build passed the parsed cookie map to
    // the HTTP layer, whose parse/re-encode round trip can mangle values
    // (percent-encoding / base64 padding), silently breaking the Laravel
    // session + XSRF match on the phone while every desktop-class client
    // in the sandbox passed. Stage markers are kept for the on-screen
    // diagnostic (see getMainPage).
    @Volatile private var rawCookieHeader: String = ""
    @Volatile private var bootDiag: String = "-"
    @Volatile private var lastApiDiag: String = "-"

    private suspend fun ensureSession(force: Boolean = false) {
        val fresh = rawCookieHeader.isNotEmpty() &&
            System.currentTimeMillis() - sessionAtMs < 10 * 60 * 1000
        if (!force && fresh) return
        val resp = app.get("$mainUrl/", headers = browserHeaders)
        val setCookies = runCatching { resp.headers.values("Set-Cookie") }
            .getOrDefault(emptyList())
        var pairs = setCookies.map { it.substringBefore(';').trim() }
            .filter { it.contains('=') }
        sessionCookies = runCatching { resp.cookies }.getOrNull().orEmpty()
        var fromMap = false
        if (pairs.isEmpty() && sessionCookies.isNotEmpty()) {
            pairs = sessionCookies.entries.map { "${it.key}=${it.value}" }
            fromMap = true
        }
        rawCookieHeader = pairs.joinToString("; ")
        csrfToken = Regex(""""csrf_token":"([^"]+)"""").find(resp.text)
            ?.groupValues?.getOrNull(1).orEmpty()
        bootDiag = "c${resp.code}|ck${pairs.size}${if (fromMap) "m" else ""}" +
            "|csrf${if (csrfToken.isNotEmpty()) 1 else 0}" +
            "|bd${if (resp.text.contains("bootstrapData")) 1 else 0}|len${resp.text.length}"
        sessionAtMs = System.currentTimeMillis()
    }

    private fun apiHeaders(): Map<String, String> {
        val xsrfRaw = rawCookieHeader.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("XSRF-TOKEN=", ignoreCase = true) }
            ?.substringAfter('=').orEmpty()
        val xsrf = runCatching { java.net.URLDecoder.decode(xsrfRaw, "UTF-8") }
            .getOrDefault(xsrfRaw)
        return mapOf(
            "User-Agent" to browserHeaders.getValue("User-Agent"),
            "Accept" to "application/json",
            "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/",
            "X-XSRF-TOKEN" to xsrf,
            "X-CSRF-TOKEN" to csrfToken,
            "Cookie" to rawCookieHeader
        )
    }

    /** One API GET with a single session-refresh retry on 401/419. */
    private suspend fun apiGet(path: String): JSONObject? {
        ensureSession()
        var resp = runCatching {
            app.get("$mainUrl$path", headers = apiHeaders())
        }.getOrNull() ?: run { lastApiDiag = "exc"; return null }
        lastApiDiag = apiDiagOf(resp)
        if (resp.code == 401 || resp.code == 419) {
            ensureSession(force = true)
            resp = runCatching {
                app.get("$mainUrl$path", headers = apiHeaders())
            }.getOrNull() ?: run { lastApiDiag = "exc2"; return null }
            lastApiDiag = apiDiagOf(resp) + "r"
        }
        if (resp.code != 200) return null
        return runCatching { JSONObject(resp.text) }.getOrNull()
    }

    private fun apiDiagOf(resp: com.lagradost.nicehttp.NiceResponse): String {
        val cfm = runCatching { resp.headers["cf-mitigated"] }.getOrNull()
        return "c${resp.code}|cfm${if (cfm != null) 1 else 0}"
    }

    // ---------- cards / catalog ----------

    private fun titleCard(o: JSONObject): SearchResponse? {
        val id = o.optInt("id")
        if (id == 0) return null
        val title = o.optString("name").trim()
        if (title.isEmpty()) return null
        val url = "$mainUrl/title/$id"
        val poster = o.optString("poster").takeIf { it.isNotEmpty() }
        // The operator mistypes part of the catalog: real series (Vikings,
        // 6 seasons, 89 episode videos) arrive with type="movie". A title
        // with seasons is a series whatever the type field claims.
        val isSeries = o.optString("type") == "series" || o.optInt("seasons_count") > 0
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun paginatedCards(json: JSONObject?): List<SearchResponse> {
        val data = json?.optJSONObject("pagination")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SearchResponse>(data.length())
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.let { titleCard(it)?.let { c -> out += c } }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = apiGet("/api/v1/titles?${request.data}&perPage=24&page=$page")
        val pag = json?.optJSONObject("pagination")
        val hasNext = pag != null && pag.optInt("current_page") < pag.optInt("last_page")
        val items = paginatedCards(json)
        // v16 diagnostic: an empty first page must SAY why on screen —
        // v15 failed silently on-device and the stage codes below are the
        // one-screenshot discriminator (bootstrap vs API, cookies issued
        // or not, csrf/bootstrapData present or not, cf-mitigated or not).
        if (items.isEmpty() && page == 1) {
            throw ErrorLoadingException("EGY-DIAG boot[$bootDiag] api[$lastApiDiag]")
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return paginatedCards(apiGet("/api/v1/titles?query=$q&perPage=30"))
    }

    // ---------- videos ----------

    /** Every server row of a title, all pages (row cap is a safety valve). */
    private suspend fun titleVideos(titleId: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page = 1
        while (page <= 10) {
            val json = apiGet("/api/v1/videos?title_id=$titleId&perPage=100&page=$page") ?: break
            val pag = json.optJSONObject("pagination") ?: break
            val data = pag.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.let { out += it }
            }
            // This API uses simple pagination (last_page/total are null):
            // a full page means there may be more; a short page is the end.
            if (data.length() < 100) break
            page++
        }
        return out
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("/title/").substringBefore('/').substringBefore('?')
            .toIntOrNull() ?: throw ErrorLoadingException("Bad title url")
        val json = apiGet("/api/v1/titles/$id")
            ?: throw ErrorLoadingException("Title fetch failed EGY-DIAG boot[$bootDiag] api[$lastApiDiag]")
        val t = json.optJSONObject("title") ?: throw ErrorLoadingException("No title data")
        val title = t.optString("name").trim().ifEmpty { throw ErrorLoadingException("No title") }
        val poster = t.optString("poster").takeIf { it.isNotEmpty() }
        val plot = t.optString("description").takeIf { it.isNotEmpty() }
        val year = t.optString("release_date").take(4).toIntOrNull()
        val tags = parseTags(t)

        // Structural series test (the type field lies for part of the
        // catalog — see titleCard): seasons in the payload or a seasons
        // count make it a series regardless of the claimed type.
        val seasonsData = json.optJSONObject("seasons")?.optJSONArray("data")
        val isSeries = t.optString("type") == "series" ||
            t.optInt("seasons_count") > 0 || (seasonsData?.length() ?: 0) > 0
        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, "ca://$id/0") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // Episodes are built from the title's video rows: each row
            // carries its episode_id + season/episode numbers, so an
            // episode exists exactly when it has at least one server.
            val byEpisode = LinkedHashMap<Int, JSONObject>()
            for (v in titleVideos(id)) {
                if (v.isNull("episode_id")) continue
                val epId = v.optInt("episode_id")
                if (epId != 0 && !byEpisode.containsKey(epId)) byEpisode[epId] = v
            }
            val episodes = byEpisode.entries
                .map { (epId, v) ->
                    val s = v.optInt("season_num", 1)
                    val e = v.optInt("episode_num", 1)
                    newEpisode("ca://$id/$epId/$s/$e") {
                        this.season = s
                        this.episode = e
                        this.name = "الحلقة $e"
                    }
                }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes with servers yet")
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun parseTags(t: JSONObject): List<String> {
        val g = t.opt("genre") ?: return emptyList()
        return when (g) {
            is String -> g.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            is org.json.JSONArray -> (0 until g.length()).mapNotNull { i ->
                val item = g.opt(i)
                when (item) {
                    is String -> item
                    is JSONObject -> item.optString("name").takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
            else -> emptyList()
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
        val parts = data.removePrefix("ca://").split('/')
        val titleId = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val episodeId = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val wantSeason = parts.getOrNull(2)?.toIntOrNull()
        val wantEpisode = parts.getOrNull(3)?.toIntOrNull()

        val videos = titleVideos(titleId)
        // Matching cascade against the operator's messy linkage: exact
        // episode_id first, then season/episode numbers; a "movie" whose
        // rows all carry episode ids falls back to every row.
        var mode = "id"
        var targets = if (episodeId == 0) {
            mode = "null"
            videos.filter { it.isNull("episode_id") }
        } else {
            videos.filter { !it.isNull("episode_id") && it.optInt("episode_id") == episodeId }
        }
        if (targets.isEmpty() && wantSeason != null && wantEpisode != null) {
            mode = "se"
            targets = videos.filter {
                it.optInt("season_num") == wantSeason && it.optInt("episode_num") == wantEpisode
            }
        }
        if (targets.isEmpty() && episodeId == 0 && videos.isNotEmpty()) {
            mode = "all"
            targets = videos
        }
        val sortedTargets = targets.sortedBy { it.optInt("order") }
        diag += "vids:${videos.size}/${sortedTargets.size}"
        diag += "m:$mode"

        var dug = 0
        var extracted = 0
        for (v in sortedTargets) {
            val src = v.optString("src").trim()
            if (src.isEmpty()) continue
            val serverName = v.optString("name").trim()
                .ifEmpty { src.substringAfter("://").substringBefore('/') }

            // A direct file handed over as-is.
            if (src.contains(".m3u8")) {
                callback(newExtractorLink(serverName, serverName, src, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                dug++
                continue
            }
            if (src.contains(".mp4")) {
                callback(newExtractorLink(serverName, serverName, src, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                dug++
                continue
            }

            // Dig the embed page locally: plain JW file:/sources first.
            val text = runCatching {
                app.get(src, headers = browserHeaders, referer = "$mainUrl/").text
            }.getOrNull()
            var emitted = false
            if (text != null) {
                val clean = text.replace("\\/", "/")
                val playlist = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                    .find(clean)?.value
                val file = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
                    .find(clean)?.value
                when {
                    playlist != null -> {
                        callback(newExtractorLink(serverName, serverName, playlist, ExtractorLinkType.M3U8) {
                            this.referer = src
                            this.quality = Qualities.Unknown.value
                        })
                        emitted = true
                    }
                    file != null -> {
                        callback(newExtractorLink(serverName, serverName, file, ExtractorLinkType.VIDEO) {
                            this.referer = src
                            this.quality = Qualities.Unknown.value
                        })
                        emitted = true
                    }
                }
            }
            if (emitted) {
                dug++
            } else {
                // Fallback: CloudStream's built-in extractors know many hosts.
                val ok = runCatching {
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false)
                if (ok) extracted++
            }
        }
        diag += "dig:$dug"
        diag += "extr:$extracted"
        emitDebug(diag, callback)
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
