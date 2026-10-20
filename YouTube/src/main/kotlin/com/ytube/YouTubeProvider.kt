package com.ytube

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import java.security.MessageDigest

/**
 * YouTube provider — built on the public InnerTube API (WEB + MWEB
 * clients), all shapes verified live 2026-10-20:
 *
 *  - search (WEB): videos = videoRenderer; playlists and channels
 *    come back as classic renderers or the newer lockupViewModel
 *    (contentType tells them apart; contentId carries the id).
 *  - channel playlists: the channel's «قوائم التشغيل» tab params
 *    are a constant tab address; with the light MWEB client the tab
 *    returns compactPlaylistRenderer rows (~0.6 MB vs 7.5 MB WEB).
 *  - playlist / channel-uploads items: browse "VL"+id with MWEB,
 *    video lockups, paged by continuation tokens.
 *  - a channel opens as its uploads playlist (UU…) series.
 *  - playback + downloads: watch URLs go to the app's built-in
 *    YoutubeExtractor; the app's download action works on its links.
 *  - sign-in (optional, plugin settings): the user signs into their
 *    own Google account in a WebView (or pastes their cookies);
 *    authenticated InnerTube calls then carry Cookie + SAPISIDHASH
 *    and «قوائمي» lists the account's own playlists (FEplaylists).
 *    The password never touches this code.
 */
class YouTubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW"
    private val webUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val mwebUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    /** The channel «قوائم التشغيل» tab address (channel-independent). */
    private val playlistsTabParams = "EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA"

    /** Spotlight music channels (verified ids): Rotana, Mazzika. */
    private val spotlightChannels = listOf(
        "UCNhqvQMXIgRfjAGmxQqdNRw",
        "UCxIoihijwX4BvHjPGjKrovg"
    )

    override val mainPage = mainPageOf(
        "mine" to "قوائمي",
        "spot" to "قوائم تشغيل القنوات",
        "pls" to "قوائم تشغيل أغاني",
        "trend" to "أغاني رائجة"
    )

    // ------------------------------------------------------------------
    // InnerTube transport (raw HTTP — no coroutines on plain threads)
    // ------------------------------------------------------------------

    private fun clientJson(mweb: Boolean): JSONObject = JSONObject()
        .put("clientName", if (mweb) "MWEB" else "WEB")
        .put("clientVersion", if (mweb) "2.20241001.01.00" else "2.20240111.09.00")
        .put("hl", "ar")
        .put("gl", "EG")

    private fun sapisidHash(): String? {
        val m = Regex("""(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID)=([^;]+)""")
            .find(YtAuth.cookies) ?: return null
        val ts = System.currentTimeMillis() / 1000
        val data = "$ts ${m.groupValues[1]} https://www.youtube.com"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(data.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hex"
    }

    private fun itube(
        endpoint: String,
        payload: JSONObject,
        mweb: Boolean,
        auth: Boolean = false
    ): JSONObject? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            payload.put(
                "context",
                JSONObject().put("client", clientJson(mweb))
            )
            conn = java.net.URL(
                "https://www.youtube.com/youtubei/v1/$endpoint?key=$apiKey"
            ).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", if (mweb) mwebUA else webUA)
            conn.setRequestProperty("Accept-Language", "ar-EG,ar;q=0.9,en;q=0.8")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            if (auth && YtAuth.cookies.isNotBlank()) {
                conn.setRequestProperty("Cookie", YtAuth.cookies)
                sapisidHash()?.let { conn.setRequestProperty("Authorization", it) }
                conn.setRequestProperty("X-Origin", "https://www.youtube.com")
            }
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                null
            } else {
                val raw = conn.inputStream
                val stream = if ((conn.contentEncoding ?: "").contains("gzip")) {
                    java.util.zip.GZIPInputStream(raw)
                } else {
                    raw
                }
                JSONObject(stream.bufferedReader(Charsets.UTF_8).readText())
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ------------------------------------------------------------------
    // JSON walking + collectors (classic renderers AND lockups)
    // ------------------------------------------------------------------

    private fun walk(o: Any?, visit: (JSONObject) -> Unit) {
        when (o) {
            is JSONObject -> {
                visit(o)
                val keys = o.keys()
                while (keys.hasNext()) {
                    walk(o.opt(keys.next()), visit)
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) walk(o.opt(i), visit)
            }
        }
    }

    /** simpleText / runs / content text of a text object. */
    private fun textOf(o: JSONObject?): String {
        if (o == null) return ""
        val st = o.optString("simpleText")
        if (st.isNotBlank()) return st
        val runs = o.optJSONArray("runs")
        if (runs != null) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return o.optString("content")
    }

    private fun cleanThumb(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url
        if (u.startsWith("//")) u = "https:$u"
        // ytimg thumbnails work without their query string.
        if (u.contains("i.ytimg.com")) u = u.substringBefore("?")
        return u
    }

    private fun bestThumb(thumbnails: JSONArray?): String? {
        if (thumbnails == null || thumbnails.length() == 0) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until thumbnails.length()) {
            val t = thumbnails.optJSONObject(i) ?: continue
            val w = t.optInt("width")
            if (w >= bestW) {
                bestW = w
                best = t.optString("url")
            }
        }
        return cleanThumb(best)
    }

    private fun lockupThumb(lk: JSONObject): String? {
        val ci = lk.optJSONObject("contentImage") ?: return null
        // video/channel lockups: contentImage.thumbnailViewModel.image.sources
        val direct = ci.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")?.optJSONArray("sources")
        if (direct != null) return bestThumb(direct)
        // playlist lockups: collectionThumbnailViewModel.primaryThumbnail…
        val coll = ci.optJSONObject("collectionThumbnailViewModel")
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")?.optJSONArray("sources")
        if (coll != null) return bestThumb(coll)
        return null
    }

    /** First badge text inside a lockup that looks like a duration/count. */
    private fun lockupBadge(lk: JSONObject, pattern: Regex): String? {
        var out: String? = null
        walk(lk) { o ->
            if (out == null && o.has("thumbnailBadgeViewModel")) {
                val t = o.optJSONObject("thumbnailBadgeViewModel")
                    ?.optString("text") ?: ""
                if (pattern.containsMatchIn(t)) out = t
            }
        }
        return out
    }

    private data class YtVideo(
        val id: String,
        val title: String,
        val thumb: String?,
        val duration: String?
    )

    private data class YtPlaylist(
        val id: String,
        val title: String,
        val thumb: String?,
        val count: String?
    )

    private data class YtChannel(
        val id: String,
        val title: String,
        val thumb: String?
    )

    private fun collectVideos(root: JSONObject?): List<YtVideo> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, YtVideo>()
        walk(root) { o ->
            val vr = o.optJSONObject("videoRenderer")
            if (vr != null) {
                val id = vr.optString("videoId")
                if (id.isNotBlank() && !out.containsKey(id)) {
                    out[id] = YtVideo(
                        id,
                        textOf(vr.optJSONObject("title")),
                        bestThumb(vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")),
                        vr.optJSONObject("lengthText")?.let { textOf(it) }
                            .takeIf { it?.isNotBlank() == true }
                    )
                }
            }
            val lk = o.optJSONObject("lockupViewModel")
            if (lk != null && lk.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                val id = lk.optString("contentId")
                if (id.isNotBlank() && !out.containsKey(id)) {
                    val title = textOf(
                        lk.optJSONObject("metadata")
                            ?.optJSONObject("lockupMetadataViewModel")
                            ?.optJSONObject("title")
                    )
                    out[id] = YtVideo(
                        id,
                        title,
                        lockupThumb(lk),
                        lockupBadge(lk, Regex("""^\d+:\d+"""))
                    )
                }
            }
        }
        return out.values.toList()
    }

    private fun collectPlaylists(root: JSONObject?): List<YtPlaylist> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, YtPlaylist>()
        fun add(p: YtPlaylist) {
            if (p.id.isNotBlank() && p.title.isNotBlank() && !out.containsKey(p.id)) {
                out[p.id] = p
            }
        }
        walk(root) { o ->
            val cp = o.optJSONObject("compactPlaylistRenderer")
            if (cp != null) {
                add(
                    YtPlaylist(
                        cp.optString("playlistId"),
                        textOf(cp.optJSONObject("title")),
                        bestThumb(cp.optJSONObject("thumbnail")?.optJSONArray("thumbnails")),
                        textOf(cp.optJSONObject("videoCountText")).ifBlank { null }
                    )
                )
            }
            val pr = o.optJSONObject("playlistRenderer")
            if (pr != null) {
                add(
                    YtPlaylist(
                        pr.optString("playlistId"),
                        textOf(pr.optJSONObject("title")),
                        bestThumb(pr.optJSONArray("thumbnails")?.optJSONObject(0)
                            ?.optJSONArray("thumbnails")),
                        pr.opt("videoCount")?.toString()
                    )
                )
            }
            val lk = o.optJSONObject("lockupViewModel")
            if (lk != null && lk.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val title = textOf(
                    lk.optJSONObject("metadata")
                        ?.optJSONObject("lockupMetadataViewModel")
                        ?.optJSONObject("title")
                )
                add(
                    YtPlaylist(
                        lk.optString("contentId"),
                        title,
                        lockupThumb(lk),
                        lockupBadge(lk, Regex("""\d+"""))
                    )
                )
            }
        }
        return out.values.toList()
    }

    private fun collectChannels(root: JSONObject?): List<YtChannel> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, YtChannel>()
        walk(root) { o ->
            val cr = o.optJSONObject("channelRenderer")
            if (cr != null) {
                val id = cr.optString("channelId")
                if (id.isNotBlank() && !out.containsKey(id)) {
                    out[id] = YtChannel(
                        id,
                        textOf(cr.optJSONObject("title")),
                        bestThumb(cr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                    )
                }
            }
            val lk = o.optJSONObject("lockupViewModel")
            if (lk != null && lk.optString("contentType") == "LOCKUP_CONTENT_TYPE_CHANNEL") {
                val id = lk.optString("contentId")
                if (id.isNotBlank() && !out.containsKey(id)) {
                    val title = textOf(
                        lk.optJSONObject("metadata")
                            ?.optJSONObject("lockupMetadataViewModel")
                            ?.optJSONObject("title")
                    )
                    out[id] = YtChannel(id, title, lockupThumb(lk))
                }
            }
        }
        return out.values.toList()
    }

    private fun continuationOf(root: JSONObject?): String? {
        if (root == null) return null
        var tok: String? = null
        walk(root) { o ->
            if (tok == null) {
                val classic = o.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!classic.isNullOrBlank()) {
                    tok = classic
                    return@walk
                }
                // New format: continuationItemViewModel wraps the command.
                val vm = o.optJSONObject("continuationItemViewModel")
                val t = vm?.optJSONObject("continuationCommand")
                    ?.optJSONObject("innertubeCommand")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!t.isNullOrBlank()) tok = t
            }
        }
        return tok
    }

    // ------------------------------------------------------------------
    // Card builders
    // ------------------------------------------------------------------

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    private fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"
    private fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"

    private fun videoCard(v: YtVideo): SearchResponse =
        newMovieSearchResponse(v.title, watchUrl(v.id), TvType.Movie) {
            this.posterUrl = v.thumb
        }

    private fun playlistCard(p: YtPlaylist): SearchResponse {
        val label = if (!p.count.isNullOrBlank()) {
            "${p.title} (${p.count})"
        } else {
            p.title
        }
        return newTvSeriesSearchResponse(label, playlistUrl(p.id), TvType.TvSeries) {
            this.posterUrl = p.thumb
        }
    }

    private fun channelCard(c: YtChannel): SearchResponse =
        newTvSeriesSearchResponse(c.title, channelUrl(c.id), TvType.TvSeries) {
            this.posterUrl = c.thumb
        }

    private fun signinCard(): SearchResponse =
        newMovieSearchResponse(
            "🔐 سجّل دخولك من إعدادات الإضافة",
            "ytube://signin",
            TvType.Movie
        ) {}

    // ------------------------------------------------------------------
    // Home sections
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return when (request.data) {
            "mine" -> {
                if (!YtAuth.signedIn()) {
                    newHomePageResponse(request.name, listOf(signinCard()), false)
                } else {
                    val resp = itube(
                        "browse",
                        JSONObject().put("browseId", "FEplaylists"),
                        mweb = true,
                        auth = true
                    )
                    val pls = collectPlaylists(resp)
                    if (pls.isEmpty()) {
                        newHomePageResponse(request.name, listOf(signinCard()), false)
                    } else {
                        newHomePageResponse(request.name, pls.map { playlistCard(it) }, false)
                    }
                }
            }
            "spot" -> {
                val all = ArrayList<YtPlaylist>()
                for (uc in spotlightChannels) {
                    val resp = itube(
                        "browse",
                        JSONObject()
                            .put("browseId", uc)
                            .put("params", playlistsTabParams),
                        mweb = true
                    )
                    all += collectPlaylists(resp)
                }
                val seen = HashSet<String>()
                val uniq = all.filter { seen.add(it.id) }.take(30)
                newHomePageResponse(request.name, uniq.map { playlistCard(it) }, false)
            }
            "pls" -> {
                val resp = itube(
                    "search",
                    JSONObject()
                        .put("query", "قوائم تشغيل اغاني")
                        .put("params", "EgIQAw=="),
                    mweb = false
                )
                newHomePageResponse(
                    request.name,
                    collectPlaylists(resp).map { playlistCard(it) },
                    false
                )
            }
            else -> { // trend
                val resp = itube(
                    "search",
                    JSONObject()
                        .put("query", "اغاني عربية")
                        .put("params", "EgIQAQ=="),
                    mweb = false
                )
                newHomePageResponse(
                    request.name,
                    collectVideos(resp).map { videoCard(it) },
                    false
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Search — videos + playlists + channels in one merged result.
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val vids = itube(
            "search",
            JSONObject().put("query", query).put("params", "EgIQAQ=="),
            mweb = false
        )
        out += collectVideos(vids).take(15).map { videoCard(it) }
        val pls = itube(
            "search",
            JSONObject().put("query", query).put("params", "EgIQAw=="),
            mweb = false
        )
        out += collectPlaylists(pls).take(15).map { playlistCard(it) }
        val chs = itube(
            "search",
            JSONObject().put("query", query).put("params", "EgIQAg=="),
            mweb = false
        )
        out += collectChannels(chs).take(10).map { channelCard(it) }
        return out
    }

    // ------------------------------------------------------------------
    // Browse paging (playlist items / channel uploads)
    // ------------------------------------------------------------------

    /** Videos of a browse target, following continuations. */
    private fun browseVideos(
        browseId: String,
        maxPages: Int,
        auth: Boolean = false
    ): Pair<List<YtVideo>, JSONObject?> {
        val all = LinkedHashMap<String, YtVideo>()
        var firstResp: JSONObject? = null
        var token: String? = null
        var page = 0
        while (page < maxPages) {
            val payload = JSONObject()
            if (token == null) {
                payload.put("browseId", browseId)
            } else {
                payload.put("continuation", token)
            }
            val resp = itube("browse", payload, mweb = true, auth = auth) ?: break
            if (firstResp == null) firstResp = resp
            for (v in collectVideos(resp)) {
                if (!all.containsKey(v.id)) all[v.id] = v
            }
            token = continuationOf(resp)
            if (token == null) break
            page++
        }
        return all.values.toList() to firstResp
    }

    /** header.pageHeaderRenderer.pageTitle / hero image of a browse response. */
    private fun browseTitle(root: JSONObject?): String? {
        if (root == null) return null
        return root.optJSONObject("header")
            ?.optJSONObject("pageHeaderRenderer")
            ?.optString("pageTitle")
            ?.ifBlank { null }
    }

    private fun browseHero(root: JSONObject?): String? {
        if (root == null) return null
        var out: String? = null
        walk(root) { o ->
            if (out == null && o.has("heroImage")) {
                // hero image view models carry image.sources arrays
                val hero = o.optJSONObject("heroImage")
                if (hero != null) {
                    walk(hero) { inner ->
                        if (out == null && inner.has("sources")) {
                            out = bestThumb(inner.optJSONArray("sources"))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun episodesOf(videos: List<YtVideo>): List<Episode> =
        videos.mapIndexed { i, v ->
            newEpisode(watchUrl(v.id)) {
                this.name = v.title
                this.episode = i + 1
                this.season = 1
                this.posterUrl = v.thumb
            }
        }

    // ------------------------------------------------------------------
    // Load dispatch
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.startsWith("ytube://signin") -> newMovieLoadResponse(
                "تسجيل الدخول إلى يوتيوب",
                url,
                TvType.Movie,
                url
            ) {
                this.plot = "افتح إعدادات إضافة YouTube (من قائمة الإضافات) " +
                    "واضغط «فتح تسجيل الدخول» — سجّل بحسابك الأصلي بنفسك، " +
                    "وبعدها قسم «قوائمي» هيعرض قوائم تشغيل حسابك. " +
                    "كلمة السر لا تصل للإضافة أبدًا؛ يُحفظ فقط كوكيز الجلسة على جهازك."
            }

            url.contains("watch?v=") -> {
                val id = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(url)
                    ?.groupValues?.get(1) ?: throw ErrorLoadingException("bad video url")
                val resp = itube(
                    "player",
                    JSONObject().put("videoId", id),
                    mweb = false
                )
                val vd = resp?.optJSONObject("videoDetails")
                val title = vd?.optString("title")?.ifBlank { null }
                    ?: "فيديو يوتيوب"
                val thumb = bestThumb(
                    vd?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                )
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = thumb
                    this.backgroundPosterUrl = thumb
                    this.plot = vd?.optString("shortDescription")
                        ?.take(600)?.ifBlank { null }
                }
            }

            url.contains("list=") -> {
                val plId = Regex("""list=([A-Za-z0-9_-]+)""").find(url)
                    ?.groupValues?.get(1) ?: throw ErrorLoadingException("bad playlist url")
                val (videos, firstResp) = browseVideos("VL$plId", maxPages = 10)
                if (videos.isEmpty()) throw ErrorLoadingException("playlist empty")
                val title = browseTitle(firstResp) ?: "قائمة تشغيل"
                val hero = browseHero(firstResp) ?: videos.firstOrNull()?.thumb
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesOf(videos)) {
                    this.posterUrl = hero
                    this.backgroundPosterUrl = hero
                }
            }

            url.contains("/channel/") -> {
                val uc = Regex("""/channel/([A-Za-z0-9_-]+)""").find(url)
                    ?.groupValues?.get(1) ?: throw ErrorLoadingException("bad channel url")
                val uploadsId = "UU" + uc.drop(2)
                val (videos, firstResp) = browseVideos("VL$uploadsId", maxPages = 4)
                if (videos.isEmpty()) throw ErrorLoadingException("channel empty")
                val title = browseTitle(firstResp) ?: "قناة يوتيوب"
                val hero = browseHero(firstResp) ?: videos.firstOrNull()?.thumb
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesOf(videos)) {
                    this.posterUrl = hero
                    this.backgroundPosterUrl = hero
                }
            }

            else -> throw ErrorLoadingException("unknown url")
        }
    }

    // ------------------------------------------------------------------
    // Playback — the app's built-in YouTube extractor does streams
    // and its links are what the app's download action saves.
    // ------------------------------------------------------------------

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(data)
            ?.groupValues?.get(1)
            ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(data)
                ?.groupValues?.get(1)
        if (id == null) {
            emitDebug(listOf("YouTube", "no-id"), callback)
            return true
        }
        var emitted = 0
        runCatching {
            loadExtractor(watchUrl(id), data, subtitleCallback) { link ->
                emitted++
                callback(link)
            }
        }
        if (emitted == 0) emitDebug(listOf("YouTube", "ext=0", "id=$id"), callback)
        return true
    }
}
