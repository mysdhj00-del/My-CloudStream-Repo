package com.movieboxin

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MovieBoxIN v1 — faithful clean-room reimplementation of the
 * CNC MovieBoxProviderIN flow (CNC Verse, verified live 2026-11-16:
 * still v56, 92,272 B, fileHash sha256-376043d8…8236ab — no newer
 * version; NO Kotlin source is published anywhere, everything here
 * was recovered from the dex + live traces, no code copied, no .cs3
 * redistributed). Study: ~/workspace/moviebox-study/NOTES.md,
 * trace: ~/workspace/movieboxin-addon/trace.py + evidence/.
 *
 * The chain, exactly as IN does it:
 * - HOME: dynamic server tabs via wefeed-mobile-bff/tab-operating
 *   (signed, region IN — the tabs on the user's screenshot:
 *   "Today's new updates", "✨Arabic Dubbed✨", "Boys' Love💙").
 *   Response: data.sections[] {title, type}; CUSTOM ->
 *   customData.subjects[].subject, BANNER -> banners[].subject,
 *   SUBJECTS_MOVIE -> subjects[]. The URL hardcodes page=1, so the
 *   home is one fetch, hasNext=false. Fallback when the mobile
 *   gateway 403s (region gate): the H5 twin on moviebox.sg
 *   (wefeed-h5api-bff/tab-operating -> data.operatingList[], same
 *   section model, subjects[] directly) — verified live 200 from
 *   the sandbox, 36 sections / 383 subjects, 2026-11-16.
 * - SEARCH: POST wefeed-mobile-bff/subject-api/search/v2 (signed,
 *   {"page":1,"perPage":20,"keyword":...}); fallback H5 POST
 *   wefeed-h5api-bff/subject/search (proven live: "Prison Break"
 *   -> Prison Break S1-S5 type 2 + films type 1).
 * - DETAIL: GET wefeed-h5api-bff/detail?detailPath= (movie-box.co
 *   twin in the dex; moviebox.sg used here, proven live) ->
 *   data.subject (+ data.resource.seasons[] {se, maxEp} for series;
 *   mobile season-info is the fallback when detail has none).
 *   Series episodes are generated 1..maxEp per season, all in ONE
 *   card, se/ep carried into playback.
 * - PLAYBACK: guest JWT (mobile gateway first, web host fallback)
 *   + X-Client-Token + x-tr-signature (HmacMD5 over the canonical
 *   string) -> subject/play on movie-box.co / moviebox.sg ->
 *   data.streams[]/dash[]/hls[] (signCookie per stream). Referer is
 *   the FULL player-page URL; detailPath goes RAW (no encoding);
 *   VIDEO links get Origin + full Referer + Pixel 9 Chrome 154 UA
 *   + the signed cookie under the stream's own signHeaderKey
 *   (default "X-MB-Token"); other types get Referer
 *   https://movie-box.co/ + desktop Chrome 154 UA. This exact
 *   shape is what made playback WORK on the user's phone in
 *   HorrorMovies v17 (2026-11-15 22:48) after v16 failed.
 * - SUBTITLES: MovieBox's own captions ONLY (his order 2026-11-16
 *   01:11/01:12 — they already exist ready-made, EN + AR; NO
 *   OpenSubtitles, NO translation pipeline, NO rebuild). H5
 *   caption endpoint first (moviebox.sg/wefeed-h5api-bff/subject/
 *   caption?format=&id=<real stream id> — the only caption route
 *   answering 200 from a US IP; id=subjectId alone returns an
 *   empty array, a missing id is a 400 "empty id"), mobile
 *   get-ext-captions / get-stream-captions as fallback. Caption
 *   object fields are lan / lanName ("Arabic"/"English", Latin) /
 *   url — there is NO "language" field. SubtitleFile is emitted
 *   as (lang, url) with NO headers.
 * - THE ONLY CHANGE vs the original IN (his order 2026-11-16):
 *   a DUAL option «الإنجليزية + العربية» merging the two
 *   existing MovieBox tracks (relay /relay/mergesubs first, local
 *   merge + /relay/store fallback). The singles stay exactly as
 *   they are. Options in his literal order: «الإنجليزية»,
 *   «العربية», «الإنجليزية + العربية» (dual). No trailer in v1
 *   AT ALL (his explicit order — he will ask for it himself if
 *   he wants it): no youtubeTrailerId, no trailerFor, no trailer
 *   code anywhere in this module.
 *
 * HONEST LIMIT (proven live, see NOTES.md): the mobile gateway
 * (tab-operating, search/v2, season-info, captions) answers 403
 * "Service not available in current region" from a US datacenter
 * IP, and subject/play returns empty streams even with a valid
 * guest JWT — so playback + captions + the IN-region tabs can NOT
 * be proven from the sandbox. The H5 routes (home tabs, search,
 * detail, captions) DO answer from here and are the live-verified
 * fallbacks; the mobile routes are the ones that work on his
 * phone (the original v56 WORKS there, his word). Nothing here
 * is "working" until he confirms on his phone after a full close
 * and reopen. DEBUG ("MovieBoxIN v1 | ... | sub=...") is emitted
 * whenever links are zero OR the dual is missing, so a failure
 * is never silent.
 */
class MovieBoxINProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBoxIN"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "tabs" to "MovieBoxIN"
    )

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val mbWebBase = "https://moviebox.sg"
    private val mbMobileBase = "https://apig.inmoviebox.com"
    private val mbApi3Base = "https://api3.aoneroom.com"
    private val mbSubsRelayBase =
        "https://anime-subs-relay.mysdhj00-del.deno.net"
    private val mbRankCategoryType = "4516404531735022304"
    private val mbJsonHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "application/json, text/plain, */*"
    )
    private val mbUaToken =
        "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; " +
            "sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)"
    private val mbUaPlayChrome =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    /** Arabic-name normalizer (diacritics/tatweel stripped) — only
     * used for caption lanName matching (see mbIsArabicCaption). */
    private fun normalizeArabic(s: String): String {
        var t = s.replace(Regex("""[\u064B-\u0652\u0670\u0640]"""), "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
        return t
    }

    // ---------------------------------------------------------------
    // Home — dynamic tabs (IN: tab-operating; H5 twin as fallback)
    // ---------------------------------------------------------------
    /** One subject JSON object -> a card. subjectType 2 is a
     * series, everything else a movie (the dex mapping: 1 -> Movie,
     * 2 -> TvSeries, default -> Movie). */
    private fun parseSubject(o: JSONObject): SearchResponse? {
        val dp = o.optString("detailPath")
        if (dp.isBlank()) return null
        val title = o.optString("title")
        if (title.isBlank()) return null
        val poster = o.optJSONObject("cover")
            ?.optString("url")?.ifBlank { null }
        val url = "$mbWebBase/ar/moviedetail/$dp"
        return if (o.optInt("subjectType", 1) == 2) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    /** Subjects of one tab-operating section, tolerant of both
     * shapes: mobile (CUSTOM -> customData.subjects[].subject,
     * BANNER -> banners[].subject, SUBJECTS_MOVIE -> subjects[])
     * and H5 (operatingList items carry subjects[] directly and
     * banner.items[] with a nested "subject" or top-level fields).
     * Dedup inside the section by detailPath. */
    private fun sectionSubjects(section: JSONObject): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        fun addOne(o: JSONObject?) {
            if (o == null) return
            val subj = o.optJSONObject("subject") ?: o
            val dp = subj.optString("detailPath")
            if (dp.isBlank() || !seen.add(dp)) return
            parseSubject(subj)?.let { out += it }
        }
        fun addArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) addOne(arr.optJSONObject(i))
        }
        val type = section.optString("type")
        when (type) {
            "CUSTOM" -> {
                val cd = section.optJSONObject("customData")
                if (cd != null) {
                    addArray(cd.optJSONArray("subjects"))
                    if (cd.has("subject")) addOne(cd.optJSONObject("subject"))
                    if (out.isEmpty() && cd.optString("detailPath").isNotBlank()) {
                        addOne(cd)
                    }
                }
            }
            "BANNER" -> {
                val banner = section.optJSONObject("banner")
                addArray(banner?.optJSONArray("items"))
                addArray(section.optJSONArray("banners"))
            }
            else -> addArray(section.optJSONArray("subjects"))
        }
        if (out.isEmpty()) {
            // Generic fallback: whatever subject array the section
            // carries (covers APPOINTMENT_LIST and any new type).
            addArray(section.optJSONArray("subjects"))
            if (out.isEmpty()) {
                val cd = section.optJSONObject("customData")
                if (cd != null) addArray(cd.optJSONArray("subjects"))
            }
        }
        return out
    }

    /** Mobile tab-operating (IN's own route, signed, region IN).
     * Returns the sections array, or null on any failure. */
    private suspend fun fetchMobileTabs(): JSONArray? {
        return runCatching {
            // The dex URL hardcodes page=1&tabId=0 and appends a
            // version after "version="; the version code is used
            // (the version name is the other candidate if the
            // server ever rejects this).
            val url = "$mbApi3Base/wefeed-mobile-bff/tab-operating" +
                "?page=1&tabId=0&version=50020042"
            val headers = mbSignedHeaders(
                "GET", url, mbUaToken, mbClientInfoToken()
            ) + mapOf("x-play-mode" to "2")
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("sections")
        }.getOrNull()
    }

    /** H5 tab-operating twin (moviebox.sg, no signing, no token).
     * Verified live 200 from the sandbox, 2026-11-16: 36 sections,
     * 383 subjects. Returns the operatingList array, or null. */
    private suspend fun fetchH5Tabs(): JSONArray? {
        return runCatching {
            val url = "$mbWebBase/wefeed-h5api-bff/tab-operating" +
                "?page=1&tabId=0"
            val res = app.get(url, headers = mbJsonHeaders)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("operatingList")
        }.getOrNull()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // IN's tab-operating URL hardcodes page=1: the home is a
        // single fetch; there is no page 2 (the H5 twin returns
        // the same 36 sections for page=2, verified live).
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val sections = fetchMobileTabs() ?: fetchH5Tabs()
            ?: return newHomePageResponse(emptyList(), false)
        val lists = ArrayList<HomePageList>()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val title = section.optString("title")
                .ifBlank { "✨Top Picks" }
            val cards = sectionSubjects(section)
            if (cards.isEmpty()) continue
            lists += HomePageList(title, cards, isHorizontalImages = true)
        }
        return newHomePageResponse(lists, false)
    }

    // ---------------------------------------------------------------
    // Search — mobile search/v2 first (IN), H5 search fallback
    // ---------------------------------------------------------------
    private fun searchItems(root: JSONObject?): List<SearchResponse> {
        if (root == null) return emptyList()
        val data = root.optJSONObject("data") ?: root
        val arr = data.optJSONArray("items")
            ?: data.optJSONArray("subjectList")
            ?: data.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dp = o.optString("detailPath")
            if (dp.isBlank() || !seen.add(dp)) continue
            parseSubject(o)?.let { out += it }
            if (out.size >= 30) break
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // Route 1 — IN's own search/v2 (signed POST on api3).
        runCatching {
            val url = "$mbApi3Base/wefeed-mobile-bff/subject-api/search/v2"
            val body = JSONObject().apply {
                put("page", 1)
                put("perPage", 20)
                put("keyword", query)
            }.toString()
            val headers = mbSignedHeaders(
                "POST", url, mbUaToken, mbClientInfoToken(),
                body = body, contentType = "application/json; charset=utf-8"
            ).toMutableMap()
            val (token, _) = mbGuestToken()
            if (!token.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $token"
                headers["X-MB-Token"] = token
            }
            val res = app.post(
                url, headers = headers,
                requestBody = body.toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            if (res.code in 200..299) {
                val items = searchItems(
                    runCatching { JSONObject(res.text) }.getOrNull()
                )
                if (items.isNotEmpty()) return items
            }
        }
        // Route 2 — H5 search (proven live from the sandbox).
        return runCatching {
            val (token, _) = mbGuestToken()
            val body = JSONObject().apply {
                put("keyword", query)
                put("page", 1)
                put("perPage", 20)
            }.toString()
            val headers = mutableMapOf(
                "User-Agent" to browserUA,
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            )
            if (!token.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $token"
                headers["X-MB-Token"] = token
            }
            val res = app.post(
                "$mbWebBase/wefeed-h5api-bff/subject/search",
                headers = headers,
                requestBody = body.toRequestBody(
                    "application/json".toMediaType()
                )
            )
            if (res.code !in 200..299) return emptyList()
            searchItems(runCatching { JSONObject(res.text) }.getOrNull())
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------
    // Detail — movies + series (one card, all episodes)
    // ---------------------------------------------------------------
    private fun mbData(
        url: String,
        title: String,
        subjectId: String,
        se: Int,
        ep: Int
    ): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return url + "|||" + enc + "|||" + subjectId +
            "|||" + se + "|||" + ep
    }

    private suspend fun movieFallback(url: String): LoadResponse {
        val name = url.trimEnd('/').substringAfterLast('/')
            .replace('-', ' ').trim().ifBlank { "MovieBox" }
        return newMovieLoadResponse(name, url, TvType.Movie, mbData(url, name, "", 0, 0))
    }

    /** Seasons from the mobile season-info endpoint (IN's route):
     * data.seasons[] {se, maxEp}. Null when the gateway 403s. */
    private suspend fun fetchMobileSeasons(subjectId: String): JSONArray? {
        return runCatching {
            val url = "$mbApi3Base/wefeed-mobile-bff/subject-api/season-info" +
                "?subjectId=$subjectId"
            val headers = mbSignedHeaders(
                "GET", url, mbUaToken, mbClientInfoToken()
            ).toMutableMap()
            val (token, _) = mbGuestToken()
            if (!token.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $token"
                headers["X-MB-Token"] = token
            }
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("seasons")
        }.getOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val dp = url.substringAfter("/moviedetail/")
            .substringBefore("?").substringBefore("#").trim()
        if (dp.isBlank()) return movieFallback(url)
        val json = runCatching {
            app.get(
                "$mbWebBase/wefeed-h5api-bff/detail?detailPath=" +
                    java.net.URLEncoder.encode(dp, "UTF-8"),
                headers = mbJsonHeaders
            ).text
        }.getOrNull()
        val data = runCatching {
            JSONObject(json ?: "").optJSONObject("data")
        }.getOrNull()
        val subj = data?.optJSONObject("subject")
            ?: return movieFallback(url)
        val title = subj.optString("title")
        val poster = subj.optJSONObject("cover")
            ?.optString("url")?.ifBlank { null }
        val desc = subj.optString("description")
        val genre = subj.optString("genre")
        val rating = subj.optDouble("imdbRatingValue", -1.0)
            .let { if (it >= 0) it.toString() else null }
        val plot = buildString {
            if (rating != null) append("★ التقييم: $rating/10\n")
            if (genre.isNotBlank()) append("النوع: $genre\n")
            if (desc.isNotBlank()) append(desc)
        }.ifBlank { null }
        val subjectId = subj.optString("subjectId")
        val isSeries = subj.optInt("subjectType", 1) == 2
        if (!isSeries) {
            return newMovieLoadResponse(
                title.ifBlank { "MovieBox" }, url, TvType.Movie,
                mbData(url, title, subjectId, 0, 0)
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
            }
        }
        // Series: seasons live under data.resource.seasons
        // (H5 detail, proven live: Prison Break S1-S5 -> S1 22,
        // S2 22, S3 13, S4 ...); mobile season-info is the
        // fallback when detail carries none.
        var seasons = data.optJSONObject("resource")
            ?.optJSONArray("seasons")
        if (seasons == null || seasons.length() == 0) {
            if (subjectId.isNotBlank()) {
                seasons = fetchMobileSeasons(subjectId)
            }
        }
        val episodes = ArrayList<Episode>()
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(s) ?: continue
                val se = seasonObj.optInt("se", 0)
                if (se <= 0) continue
                val maxEp = seasonObj.optInt("maxEp", 0)
                if (maxEp <= 0) continue
                for (ep in 1..maxEp) {
                    if (episodes.size >= 1000) break
                    episodes += newEpisode(
                        mbData(url, title, subjectId, se, ep)
                    ) {
                        this.name = "S${se}E$ep"
                        this.season = se
                        this.episode = ep
                    }
                }
            }
        }
        return newTvSeriesLoadResponse(
            title.ifBlank { "MovieBox" }, url, TvType.TvSeries, episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------
    // DEBUG + playback — shared machinery
    // ---------------------------------------------------------------
    private suspend fun emitDebug(
        diag: List<String>,
        callback: (ExtractorLink) -> Unit
    ) {
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

    // --- Auth (protocol constants recovered from the IN dex) ---
    private val mbDeviceId: String by lazy {
        val rnd = java.security.SecureRandom()
        val bytes = ByteArray(16)
        rnd.nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** The two HMAC keys, double-base64 as stored in the dex. */
    private val mbKeyDefaultB64 =
        "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private val mbKeyAltB64 =
        "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

    private fun mbKey(doubleB64: String): ByteArray {
        val once = Base64.decode(doubleB64, Base64.DEFAULT)
        val inner = String(once, Charsets.UTF_8).trim()
        return Base64.decode(inner, Base64.DEFAULT)
    }

    private fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(s)

    /** Canonical string: METHOD\nACCEPT\nCONTENT_TYPE\nBODY_LEN\n
     * TS\nBODY_HASH\nPATH[?sorted-query] — query keys sorted
     * alphabetically, each rendered name=value (decoded) joined
     * with '&'. GET with no body => BODY_LEN and BODY_HASH are
     * empty strings; POST => length + md5 of the first 102400
     * body bytes. */
    private fun mbCanonical(
        method: String,
        url: String,
        ts: Long,
        body: String?,
        accept: String,
        contentType: String
    ): String {
        val qStart = url.indexOf('?')
        val rawPath = if (qStart >= 0) url.substring(0, qStart) else url
        val path = Regex("""^[a-z]+://[^/]+""").replace(rawPath, "")
        val pairs = ArrayList<Pair<String, String>>()
        if (qStart >= 0) {
            val query = url.substring(qStart + 1)
            if (query.isNotBlank()) {
                for (part in query.split("&")) {
                    if (part.isEmpty()) continue
                    val eq = part.indexOf('=')
                    val k = if (eq >= 0) urlDecode(part.substring(0, eq))
                    else urlDecode(part)
                    val v = if (eq >= 0) urlDecode(part.substring(eq + 1))
                    else ""
                    pairs += k to v
                }
            }
        }
        val rendered = StringBuilder()
        for (k in pairs.map { it.first }.distinct().sorted()) {
            for ((kk, vv) in pairs) {
                if (kk == k) {
                    if (rendered.isNotEmpty()) rendered.append("&")
                    rendered.append(k).append("=").append(vv)
                }
            }
        }
        val curl = if (rendered.isEmpty()) path else "$path?$rendered"
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyLen = if (bodyBytes == null) "" else bodyBytes.size.toString()
        val bodyHash = if (bodyBytes == null) "" else
            md5Hex(bodyBytes.copyOf(minOf(bodyBytes.size, 102400)))
        return listOf(
            method.uppercase(), accept, contentType,
            bodyLen, ts.toString(), bodyHash, curl
        ).joinToString("\n")
    }

    /** X-Client-Token = "<ts>,<md5(reverse(ts))>"; x-tr-signature
     * = "<ts>|2|<base64(HmacMD5(key, canonical))>". */
    private fun mbSignedHeaders(
        method: String,
        url: String,
        ua: String,
        clientInfo: String,
        body: String? = null,
        accept: String = "application/json",
        contentType: String = "application/json"
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val xct = "$ts,${md5Hex(ts.toString().reversed().toByteArray(Charsets.UTF_8))}"
        val canonical = mbCanonical(method, url, ts, body, accept, contentType)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(mbKey(mbKeyDefaultB64), "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sig = "$ts|2|" + Base64.encodeToString(sigBytes, Base64.NO_WRAP)
        return mapOf(
            "User-Agent" to ua,
            "Accept" to accept,
            "Content-Type" to contentType,
            "X-Client-Token" to xct,
            "x-tr-signature" to sig,
            "X-Client-Info" to clientInfo,
            "X-Client-Status" to "0"
        )
    }

    private fun mbClientInfoToken(): String =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"3.0.03.0529.03\"," +
            "\"version_code\":50020042,\"os\":\"android\",\"os_version\":\"16\"," +
            "\"device_id\":\"" + mbDeviceId + "\",\"install_store\":\"ps\"," +
            "\"gaid\":\"d7578036d13336cc\",\"brand\":\"google\",\"model\":\"SM-S918B\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"IN\"," +
            "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

    private fun mbClientInfoPlay(): String =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"4.0.02.0831.03\"," +
            "\"version_code\":50020126,\"os\":\"android\",\"os_version\":\"14\"," +
            "\"install_ch\":\"official\",\"device_id\":\"" + mbDeviceId + "\"," +
            "\"install_store\":\"official\",\"gaid\":\"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d\"," +
            "\"brand\":\"Google\",\"model\":\"Pixel 8\",\"system_language\":\"en\"," +
            "\"net\":\"NETWORK_WIFI\",\"region\":\"IN\",\"timezone\":\"Asia/Calcutta\"," +
            "\"sp_code\":\"\",\"X-Play-Mode\":\"1\",\"X-Idle-Data\":\"1\"," +
            "\"X-Family-Mode\":\"0\",\"X-Content-Mode\":\"0\"}"

    /** Read the guest JWT from one call: the token arrives in the
     * "x-user" response header (a JSON string with a "token"
     * field) or, failing that, in the JSON body. */
    private suspend fun mbTokenFrom(
        url: String,
        signed: Boolean,
        clientInfo: String?,
        ua: String
    ): String? {
        return try {
            val headers = if (signed && clientInfo != null) {
                mbSignedHeaders("GET", url, ua, clientInfo)
            } else {
                mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json, text/plain, */*"
                )
            }
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return null
            val xu = res.headers["x-user"] ?: res.headers["X-User"]
            val fromHeader = xu?.let {
                runCatching { JSONObject(it).optString("token") }.getOrNull()
            }?.ifBlank { null }
            if (!fromHeader.isNullOrBlank()) return fromHeader
            val body = res.text
            if (body.isBlank()) return null
            val j = runCatching { JSONObject(body) }.getOrNull()
                ?: return null
            j.optString("token").ifBlank { null }
                ?: j.optJSONObject("data")?.optString("token")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var mbCachedToken: String? = null
    @Volatile
    private var mbCachedAt: Long = 0

    /** Guest JWT (valid ~90 days; cached 1 h per session). Mobile
     * gateway first (IN order), web host fallback — the web host
     * grants the same JWT in its x-user header (verified live). */
    private suspend fun mbGuestToken(): Pair<String?, String> {
        val now = System.currentTimeMillis()
        val cached = mbCachedToken
        if (cached != null && now - mbCachedAt < 3600_000) {
            return cached to "cached"
        }
        val mobUrl = "$mbMobileBase/wefeed-mobile-bff/tab/ranking-list" +
            "?tabId=0&categoryType=$mbRankCategoryType&page=1&perPage=1"
        var tok = mbTokenFrom(
            mobUrl, signed = true,
            clientInfo = mbClientInfoToken(), ua = mbUaToken
        )
        var via = "mob"
        if (tok == null) {
            val webUrl = "$mbWebBase/wefeed-h5api-bff/ranking-list/content" +
                "?id=8614980535946986176&page=1&perPage=1"
            tok = mbTokenFrom(
                webUrl, signed = false, clientInfo = null, ua = browserUA
            )
            via = "web"
        }
        if (tok != null) {
            mbCachedToken = tok
            mbCachedAt = now
        }
        return tok to via
    }

    private fun mbStreamUrl(s: JSONObject): String? {
        val direct = s.optString("url").trim()
        if (direct.isNotBlank()) return direct
        val arr = s.optJSONArray("resolutions") ?: return null
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("url").orEmpty().trim()
            if (u.isNotBlank()) return u
        }
        return null
    }

    /** CNC-faithful emit (MovieBoxProviderIN loadLinks$emitStream,
     * byte-identical logic in v54's emitNetStream). VIDEO links get
     * Origin + the full player-page Referer + the mobile Chrome UA
     * + the signed cookie under the stream's own signHeaderKey
     * (default "X-MB-Token"); every other type gets {Referer:
     * "https://movie-box.co/", desktop Chrome 154 UA}.
     * subDomain = the play base that returned the streams,
     * subPath = detailPath, so the emitted Referer is exactly the
     * player-page URL the backend validated for the play call. */
    private suspend fun emitMovieBoxLink(
        url: String,
        label: String,
        signCookie: String?,
        signHeaderKey: String?,
        playBase: String,
        detailPath: String,
        subjectId: String,
        se: Int,
        ep: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        // Movies play with se=0&ep=0 and the dex Referer carries
        // empty detailSe/detailEp (the v17 shape proven on-device);
        // episodes carry their real se/ep in both places.
        val seEp = if (se == 0 && ep == 0) {
            "detailSe=&detailEp="
        } else {
            "detailSe=$se&detailEp=$ep"
        }
        val playerReferer = "$playBase/movies/$detailPath?id=$subjectId" +
            "&type=/movie/detail&$seEp&lang=en"
        val headers: Map<String, String> =
            if (type != ExtractorLinkType.VIDEO) {
                mapOf(
                    "Referer" to "https://movie-box.co/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
                )
            } else {
                val m = LinkedHashMap<String, String>()
                m["Origin"] = playBase
                m["Referer"] = playerReferer
                m["User-Agent"] = mbUaPlayChrome
                if (!signCookie.isNullOrBlank()) {
                    val key = signHeaderKey?.ifBlank { null } ?: "X-MB-Token"
                    m[key] = signCookie
                }
                m
            }
        val quality = when {
            label.contains("2160") -> Qualities.P2160.value
            label.contains("1440") -> Qualities.P1440.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        runCatching {
            callback(
                newExtractorLink(
                    name, label, url, type
                ) {
                    this.referer = if (type != ExtractorLinkType.VIDEO) {
                        "https://movie-box.co/"
                    } else {
                        playerReferer
                    }
                    this.quality = quality
                    this.headers = headers
                }
            )
        }
    }

    // ---------------------------------------------------------------
    // Subtitles — MovieBox's own captions ONLY (+ the dual merge).
    // No OpenSubtitles, no translation pipeline, no rebuild: the
    // singles come from MovieBox exactly as they are; the ONLY
    // addition vs the original IN is the dual option merging the
    // two existing tracks (his order 2026-11-16).
    // ---------------------------------------------------------------
    /** Is this caption object an Arabic track? Fields (proven from
     * the CNC dex + the CloudX model) are lan (code, e.g. "ar"),
     * lanName (display name, e.g. "Arabic"), url — there is NO
     * "language" field, and lanName is Latin ("Arabic"). */
    private fun mbIsArabicCaption(o: JSONObject): Boolean {
        val lan = o.optString("lan").lowercase()
        val lang = o.optString("language").lowercase()
        if (lan == "ar" || lan == "ara" || lang == "ar" || lang == "ara") return true
        val lanName = o.optString("lanName")
        if (normalizeArabic(lanName).contains("عرب")) return true
        if (lanName.lowercase().contains("arab")) return true
        return false
    }

    private fun mbIsEnglishCaption(o: JSONObject): Boolean {
        val lan = o.optString("lan").lowercase()
        val lang = o.optString("language").lowercase()
        if (lan == "en" || lan == "eng" || lang == "en" || lang == "eng") return true
        val lanName = o.optString("lanName")
        if (lanName.lowercase().contains("english")) return true
        if (normalizeArabic(lanName).contains("انجليز")) return true
        return false
    }

    /** Scan one captions array for the FIRST Arabic and the FIRST
     * English track. Returns (araUrl, engUrl) — either may be
     * null. Both come from the SAME array (the same caption call
     * that returns Arabic returns English too). */
    private fun mbPickCaptions(
        arr: JSONArray?
    ): Pair<String?, String?> {
        var ara: String? = null
        var eng: String? = null
        if (arr == null) return null to null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val capUrl = o.optString("url")
            if (capUrl.isBlank()) continue
            if (ara == null && mbIsArabicCaption(o)) ara = capUrl
            if (eng == null && mbIsEnglishCaption(o)) eng = capUrl
            if (ara != null && eng != null) break
        }
        return ara to eng
    }

    // ---- Local subtitle merge (fallback when the relay mergesubs
    // URL does not serve) ----
    private data class MbSubCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private fun mbSubTsToMs(t: String): Long {
        val clean = t.trim().replace(',', '.')
        val parts = clean.split(":")
        val secParts = parts.last().split(".")
        val secs = secParts[0].toLongOrNull() ?: 0L
        val ms = (secParts.getOrNull(1) ?: "0").padEnd(3, '0')
            .take(3).toLongOrNull() ?: 0L
        var total = secs * 1000 + ms
        if (parts.size == 3) total += (parts[0].toLongOrNull() ?: 0L) * 3600000 +
            (parts[1].toLongOrNull() ?: 0L) * 60000
        else if (parts.size == 2) total += (parts[0].toLongOrNull() ?: 0L) * 60000
        return total
    }

    private fun mbFmtMs(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return "%02d:%02d:%02d.%03d".format(h, m, s, r)
    }

    /** Tolerant VTT/SRT cue parser (SRT index lines and comma
     * timestamps are skipped/accepted; HTML tags stripped). */
    private fun mbParseSubCues(text: String): List<MbSubCue> {
        val cues = ArrayList<MbSubCue>()
        val blocks = text.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n\n")
        val tsRe = Regex(
            """(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})"""
        )
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var tsIdx = -1
            var m: MatchResult? = null
            for (i in lines.indices) {
                m = tsRe.find(lines[i])
                if (m != null) {
                    tsIdx = i
                    break
                }
            }
            if (tsIdx < 0 || m == null) continue
            val startMs = mbSubTsToMs(m.groupValues[1])
            val endMs = mbSubTsToMs(m.groupValues[2])
            val cueText = lines.subList(tsIdx + 1, lines.size)
                .joinToString("\n") { it.replace(Regex("<[^>]*>"), "") }
                .trim()
            if (cueText.isEmpty()) continue
            cues += MbSubCue(startMs, endMs, cueText)
        }
        return cues
    }

    /** Merge Arabic + English cue lists into one dual VTT:
     * every Arabic cue gains the overlapping English line(s)
     * underneath it; English cues with no Arabic overlap stay
     * on their own. Same shape the relay's mergesubs returns
     * (Arabic line first, English second). */
    private fun mbBuildDualVTT(
        araCues: List<MbSubCue>,
        engCues: List<MbSubCue>
    ): String {
        val usedEng = HashSet<Int>()
        val merged = ArrayList<MbSubCue>()
        for (a in araCues) {
            val overlapping = engCues.withIndex()
                .filter { it.value.startMs < a.endMs && it.value.endMs > a.startMs }
            val enText = overlapping.joinToString("\n") { it.value.text }
            overlapping.forEach { usedEng += it.index }
            val text = if (enText.isEmpty()) a.text else a.text + "\n" + enText
            merged += MbSubCue(a.startMs, a.endMs, text)
        }
        for ((idx, e) in engCues.withIndex()) {
            if (idx !in usedEng) merged += MbSubCue(e.startMs, e.endMs, e.text)
        }
        merged.sortBy { it.startMs }
        val out = StringBuilder("WEBVTT\n\n")
        for (c in merged) {
            out.append(mbFmtMs(c.startMs)).append(" --> ")
                .append(mbFmtMs(c.endMs)).append("\n")
                .append(c.text).append("\n\n")
        }
        return out.toString()
    }

    /** Fetch both caption files on the phone's network (which
     * reaches MovieBox), merge locally and host the result on
     * the subtitle relay. Returns the stored dual URL, or null
     * when any step fails. */
    private suspend fun mbMergeCaptionsLocal(
        araUrl: String,
        engUrl: String
    ): String? {
        return runCatching {
            val araText = app.get(araUrl).text
            val engText = app.get(engUrl).text
            if (araText.isBlank() || engText.isBlank()) return@runCatching null
            val araCues = mbParseSubCues(araText)
            val engCues = mbParseSubCues(engText)
            if (araCues.isEmpty() || engCues.isEmpty()) return@runCatching null
            val vtt = mbBuildDualVTT(araCues, engCues)
            val res = app.post(
                "$mbSubsRelayBase/relay/store",
                data = mapOf("vtt" to vtt)
            )
            JSONObject(res.text).optString("url").ifEmpty { null }
        }.getOrNull()
    }

    /** Emit subtitles from one captions array, in his literal
     * order: «الإنجليزية», «العربية», «الإنجليزية + العربية»
     * (dual). Returns the sub diagnostic: "ar+en" when the dual
     * option was emitted, "ar" / "en" for a single side, "ar+mf"
     * when both sides exist but no dual file could be built
     * (merge + local fallback both failed — the singles are
     * still emitted), or null when the array holds neither. */
    private suspend fun mbEmitFromArray(
        arr: JSONArray?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        val (ara, eng) = mbPickCaptions(arr)
        if (ara == null && eng == null) return null
        if (eng != null) subtitleCallback(SubtitleFile("الإنجليزية", eng))
        if (ara != null) subtitleCallback(SubtitleFile("العربية", ara))
        if (ara != null && eng != null) {
            val mergeUrl = "$mbSubsRelayBase/relay/mergesubs?eng=" +
                java.net.URLEncoder.encode(eng, "UTF-8") +
                "&ara=" + java.net.URLEncoder.encode(ara, "UTF-8")
            val mergeOk = runCatching {
                val r = app.get(mergeUrl)
                r.code in 200..299 && r.text.contains("WEBVTT")
            }.getOrDefault(false)
            if (mergeOk) {
                subtitleCallback(SubtitleFile("الإنجليزية + العربية", mergeUrl))
                return "ar+en"
            }
            val stored = mbMergeCaptionsLocal(ara, eng)
            if (stored != null) {
                subtitleCallback(SubtitleFile("الإنجليزية + العربية", stored))
                return "ar+en"
            }
            return "ar+mf"
        }
        if (ara != null) return "ar"
        return "en"
    }

    /** Captions array from a caption-endpoint body: the mobile
     * shape (data.extCaptions) or the H5 shape (data.captions),
     * plus top-level fallbacks. */
    private fun mbCaptionsArray(
        json: JSONObject?,
        vararg keys: String
    ): JSONArray? {
        if (json == null) return null
        val data = json.optJSONObject("data")
        for (k in keys) {
            data?.optJSONArray(k)?.let { return it }
            json.optJSONArray(k)?.let { return it }
        }
        return null
    }

    /** Captions for a title. Returns a diagnostic: "ar+en" when
     * the dual option was emitted, "ar" / "en" for a single side,
     * "ar+mf" when both sides exist but no dual file could be
     * built, else a compact "none:<route>=<code>/..." trail.
     * Route H5 first: NOT region-gated (200 from a US IP), with
     * the REAL stream id + format from the play response
     * (id=subjectId alone returns an empty captions array; a
     * missing id is a 400 "empty id"). The mobile routes stay as
     * fallback. The CNC dex emits SubtitleFile with (lang, url)
     * and NO headers, so no headers are passed with the caption
     * URL. */
    private suspend fun mbEmitCaptions(
        subjectId: String,
        detailPath: String,
        playBase: String,
        token: String,
        h5Caps: List<Pair<String, String>>,
        streamId: String?,
        resourceId: String?,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val trail = ArrayList<String>()
        // Route H5 first: NOT region-gated (200 from a US IP).
        for ((sid, fmt) in h5Caps) {
            val capUrl = "$playBase/wefeed-h5api-bff/subject/caption" +
                "?format=$fmt&id=$sid&subjectId=$subjectId" +
                "&detailPath=$detailPath"
            val headers = mbSignedHeaders(
                "GET", capUrl, mbUaToken, mbClientInfoToken()
            ) + mapOf(
                "Authorization" to "Bearer $token",
                "X-MB-Token" to token
            )
            val res = runCatching { app.get(capUrl, headers = headers) }.getOrNull()
            val code = res?.code ?: 0
            if (res == null || code !in 200..299) {
                trail += "h5=$code"
                continue
            }
            val json = runCatching { JSONObject(res.text) }.getOrNull()
            val arr = mbCaptionsArray(json, "captions", "extCaptions")
            if (arr == null || arr.length() == 0) {
                trail += "h5=$code-empty"
                continue
            }
            mbEmitFromArray(arr, subtitleCallback)?.let { return it }
            trail += "h5=$code-nocap"
        }
        // Route mobile ext-captions (subjectId only).
        runCatching {
            val extUrl = "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                "get-ext-captions?subjectId=$subjectId"
            val extHeaders = mbSignedHeaders(
                "GET", extUrl, mbUaToken, mbClientInfoToken()
            ) + mapOf(
                "Authorization" to "Bearer $token",
                "X-MB-Token" to token
            )
            val extRes = app.get(extUrl, headers = extHeaders)
            trail += "mob=${extRes.code}"
            if (extRes.code in 200..299) {
                val extJson = runCatching { JSONObject(extRes.text) }.getOrNull()
                val arr = mbCaptionsArray(extJson, "extCaptions", "captions")
                mbEmitFromArray(arr, subtitleCallback)?.let { return it }
            }
        }
        // Route mobile stream-captions (needs stream + resource ids).
        if (!streamId.isNullOrBlank() && !resourceId.isNullOrBlank()) {
            runCatching {
                val streamUrl = "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                    "get-stream-captions?subjectId=$subjectId" +
                    "&streamId=$streamId&episode=$episode&resourceId=$resourceId"
                val streamHeaders = mbSignedHeaders(
                    "GET", streamUrl, mbUaToken, mbClientInfoToken()
                ) + mapOf(
                    "Authorization" to "Bearer $token",
                    "X-MB-Token" to token
                )
                val streamRes = app.get(streamUrl, headers = streamHeaders)
                trail += "mobstr=${streamRes.code}"
                if (streamRes.code in 200..299) {
                    val streamJson = runCatching { JSONObject(streamRes.text) }.getOrNull()
                    val arr = mbCaptionsArray(streamJson, "captions", "extCaptions")
                    mbEmitFromArray(arr, subtitleCallback)?.let { return it }
                }
            }
        }
        return "none:" + trail.joinToString("/")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - t0 }
        val parts = data.split("|||")
        val realData = parts.getOrNull(0) ?: data
        val subjectId = parts.getOrNull(2).orEmpty()
        val se = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val ep = parts.getOrNull(4)?.toIntOrNull() ?: 0
        val detailPath = realData.substringAfter("/moviedetail/")
            .substringBefore("?").substringBefore("#").trim()
        var streamsFound = 0
        var emitted = 0
        var playCode = "?"
        var tokVia = "none"
        var streamId: String? = null
        var resourceId: String? = null
        var subDiag = "?"
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        if (subjectId.isBlank() || detailPath.isBlank()) {
            emitDebug(
                listOf(
                    "MovieBoxIN v1", "srv=0", "ext=0",
                    "t=${elapsed() / 1000}s", "tok=$tokVia",
                    "play=$playCode", "id-missing"
                ),
                callback
            )
            return true
        }
        val (token, via) = mbGuestToken()
        tokVia = via
        if (token != null) {
            val bases = listOf("https://movie-box.co", "https://moviebox.sg")
            for (base in bases) {
                if (emitted > 0 || elapsed() > 20000) break
                // The play Referer is the FULL player-page URL on
                // the SAME base, with the detailPath in the path.
                // detailPath goes into the play URL RAW (no
                // URL-encoding). Movies play with se=0&ep=0 and
                // carry EMPTY detailSe/detailEp in the Referer
                // (the v17 shape proven on-device); episodes
                // carry their real se/ep in both places — the
                // same rule emitMovieBoxLink applies to the
                // emitted links, so both Referers always match.
                val seEp = if (se == 0 && ep == 0) {
                    "detailSe=&detailEp="
                } else {
                    "detailSe=$se&detailEp=$ep"
                }
                val referer = "$base/movies/$detailPath?id=$subjectId" +
                    "&type=/movie/detail&$seEp&lang=en"
                val playUrl = "$base/wefeed-h5api-bff/subject/play" +
                    "?subjectId=$subjectId&se=$se&ep=$ep" +
                    "&detailPath=$detailPath" +
                    "&streamSignType=1" +
                    "&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                val headers = mbSignedHeaders(
                    "GET", playUrl, mbUaPlayChrome, mbClientInfoPlay()
                ) + mapOf(
                    "Referer" to referer,
                    "Authorization" to "Bearer $token",
                    "X-MB-Token" to token,
                    "x-play-mode" to "1",
                    "x-request-lang" to "en",
                    "x-vip-restrict" to "0",
                    "x-no-high-risk-restrict" to "0",
                    "x-source" to ""
                )
                val res = runCatching { app.get(playUrl, headers = headers) }
                    .getOrNull() ?: continue
                playCode = res.code.toString()
                if (res.code !in 200..299) continue
                val body = res.text
                if (body.isBlank()) continue
                val d = runCatching {
                    JSONObject(body).optJSONObject("data")
                }.getOrNull() ?: continue
                resourceId = d.optString("resourceId").ifBlank { null }
                    ?: resourceId
                val streamObjs = ArrayList<JSONObject>()
                for (key in listOf("streams", "dash", "hls")) {
                    val arr = d.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { streamObjs += it }
                    }
                }
                streamsFound += streamObjs.size
                if (streamObjs.isNotEmpty()) {
                    streamId = streamObjs[0].optString("id")
                        .ifBlank { null } ?: streamId
                    if (resourceId == null) {
                        resourceId = streamObjs[0].optString("resourceId")
                            .ifBlank { null }
                    }
                }
                // H5 caption candidates — (stream id, format)
                // from the play response, first id per format.
                val h5Caps = ArrayList<Pair<String, String>>()
                for (s in streamObjs) {
                    val sid = s.optString("id")
                    if (sid.isBlank()) continue
                    val surl = mbStreamUrl(s) ?: continue
                    val fmt = when {
                        surl.contains(".mpd") -> "DASH"
                        surl.contains(".m3u8") -> "HLS"
                        else -> "MP4"
                    }
                    if (h5Caps.none { it.first == sid }) h5Caps += sid to fmt
                    if (h5Caps.size >= 3) break
                }
                for (s in streamObjs) {
                    if (emitted >= 6 || elapsed() > 25000) break
                    val linkUrl = mbStreamUrl(s) ?: continue
                    val signCookie = s.optString("signCookie")
                        .ifBlank { null }
                    val signHeaderKey = s.optString("signHeaderKey")
                        .ifBlank { null }
                    val resLabel = s.optString("resolution").ifBlank {
                        s.optJSONArray("resolutions")
                            ?.optJSONObject(0)?.optString("resolution")
                            .orEmpty()
                    }
                    val q = Regex("""(2160|1440|1080|720|480|360)""")
                        .find(resLabel)?.groupValues?.get(1)
                    val label = "MovieBox" + if (q != null) " ${q}p" else ""
                    emitMovieBoxLink(
                        linkUrl, label, signCookie, signHeaderKey,
                        base, detailPath, subjectId, se, ep, counting
                    )
                }
                if (emitted > 0) {
                    subDiag = mbEmitCaptions(
                        subjectId, detailPath, base, token, h5Caps,
                        streamId, resourceId, ep, subtitleCallback
                    )
                    break
                }
            }
        }
        if (emitted == 0 || subDiag != "ar+en") {
            // A missing subtitle must never be silent again —
            // emit the DEBUG line even when playback links exist.
            emitDebug(
                listOf(
                    "MovieBoxIN v1", "srv=$streamsFound",
                    "ext=$emitted", "t=${elapsed() / 1000}s",
                    "tok=$tokVia", "play=$playCode",
                    "se=$se", "ep=$ep", "sub=$subDiag"
                ),
                callback
            )
        }
        return true
    }
}
