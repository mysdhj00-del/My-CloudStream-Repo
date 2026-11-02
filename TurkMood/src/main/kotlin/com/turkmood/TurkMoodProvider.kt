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
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * v5 (3isk transfer, 2026-11-02): the Arabic repo's 3isk provider
 * (Abodabodd/re-3arabi) plays these same MiraVD/Mwdy files on the
 * phone, so its embed dig is ported here: for every server
 * loadExtractor fails on, fetch the embed with a referer, unpack
 * P.A.C.K.E.R. eval scripts, extract direct media, follow one nested
 * iframe (bounded: 20s, max 8 dug servers). The 3isk watch-form POST
 * dance has no equivalent on this API and is NOT ported.
 *
 * v6 (2026-11-02): v5 reported on-device as 'no links found' with NO
 * DEBUG source at all — meaning loadLinks died silently before the
 * diagnostic could emit. Wrapper: any throwable becomes a visible
 * LOADLINKS_CRASH DEBUG source. Dig budget tightened to 12s / max 4.
 * v8: MiraVD/Mwdy skip the dig and go straight to WebView sniff;
 * DEBUG lines now carry a v8 marker so the phone proves which code ran.
 *
 * v7 (Faselhd WebView sniff, user-approved 2026-11-02): last resort
 * for MiraVD/Mwdy only — invisible 1x1 WebView on the UI thread
 * captures .m3u8 from the network layer, emitted with WebView
 * cookies + embed referer. COROUTINE-FREE (our CI rejects the coroutines library):
 * Handler + CountDownLatch, 10s timeout + 3s post-load grace,
 * always destroyed. Plugin passes its Context to the provider so
 * the WebView has an Activity. All other hosts unchanged.
 */
class TurkMoodProvider(private val appContext: Context? = null) : MainAPI() {
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

    // ---------- v5: 3isk-style embed dig (ported from the Arabic repo's
    // 3isk provider, Abodabodd/re-3arabi, which plays these same
    // MiraVD/Mwdy files on the phone) ----------
    // The site's own watch chain reaches host embeds through a POST dance
    // and then digs them: fetch with a referer chain, unpack P.A.C.K.E.R.
    // eval scripts, extract direct media, follow one nested iframe. The
    // POST dance itself has no equivalent on TurkMood's API (no watch
    // forms), so v5 ports the DIG half: referer-chained fetch + packer
    // unpack + media regex + nested iframe, applied to every server
    // loadExtractor fails on (MiraVD/Mwdy first among them).

    private fun jsStringUnescape(s: String): String {
        val regex = Regex("""\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}|\\.|\\n|\\r|\\t""")
        return regex.replace(s) { m ->
            val esc = m.value
            try {
                when {
                    esc.startsWith("\\x") -> esc.substring(2).toInt(16).toChar().toString()
                    esc.startsWith("\\u") -> esc.substring(2).toInt(16).toChar().toString()
                    esc == "\\n" -> "\n"
                    esc == "\\r" -> "\r"
                    esc == "\\t" -> "\t"
                    esc == "\\'" -> "'"
                    esc == "\\\"" -> "\""
                    esc == "\\\\" -> "\\"
                    else -> if (esc.length >= 2 && esc[0] == '\\') esc.substring(1) else esc
                }
            } catch (_: Exception) {
                esc
            }
        }
    }

    private fun intToBase36(n0: Int): String {
        if (n0 == 0) return "0"
        var n = n0
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(chars[n % 36])
            n /= 36
        }
        return sb.reverse().toString()
    }

    private fun parseJsStringAt(text: String, idxInit: Int): Pair<String?, Int> {
        var idx = idxInit
        if (idx >= text.length) return Pair(null, idx)
        val quote = text[idx]
        if (quote != '"' && quote != '\'') return Pair(null, idx)
        idx += 1
        val out = StringBuilder()
        while (idx < text.length) {
            val ch = text[idx]
            if (ch == '\\') {
                if (idx + 1 < text.length) {
                    out.append(text.substring(idx, idx + 2))
                    idx += 2
                } else {
                    idx++
                }
            } else if (ch == quote) {
                return Pair(jsStringUnescape(out.toString()), idx + 1)
            } else {
                out.append(ch)
                idx++
            }
        }
        return Pair(null, idx)
    }

    private fun findMatchingBrace(text: String, startIdx: Int): Int {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return -1
        var depth = 0
        var i = startIdx
        while (i < text.length) {
            val ch = text[i]
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** Unpacks one Dean Edwards P.A.C.K.E.R. eval block; null if not packed. */
    private fun unpackPacker(evalText: String): String? {
        try {
            val startFn = evalText.indexOf("function(p,a,c,k,e,d)")
            if (startFn == -1) return null
            val braceOpen = evalText.indexOf('{', startFn)
            if (braceOpen == -1) return null
            val braceClose = findMatchingBrace(evalText, braceOpen)
            if (braceClose == -1) return null
            val argsStart = evalText.indexOf('(', braceClose)
            if (argsStart == -1) return null
            var i = argsStart + 1
            while (i < evalText.length && evalText[i].isWhitespace()) i++
            val (pVal, newI) = parseJsStringAt(evalText, i); i = newI
            if (pVal == null) return null
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
            val aMatch = Regex("""\d+""").find(evalText.substring(i)) ?: return null
            i += aMatch.range.last + 1
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
            val cMatch = Regex("""\d+""").find(evalText.substring(i)) ?: return null
            val cVal = cMatch.value.toInt()
            i += cMatch.range.last + 1
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
            val kList = mutableListOf<String>()
            if (i < evalText.length && (evalText[i] == '"' || evalText[i] == '\'')) {
                val (kStr, _) = parseJsStringAt(evalText, i)
                if (kStr != null) kList.addAll(kStr.split("|"))
            } else {
                val m2 = Regex("""(['"])(.*?)\1\s*\.split\s*\(\s*['"]\|['"]\s*\)""", RegexOption.DOT_MATCHES_ALL).find(evalText)
                if (m2 != null) kList.addAll(m2.groupValues[2].split("|"))
            }
            var p = pVal
            for (idx in cVal - 1 downTo 0) {
                val key = intToBase36(idx)
                if (idx < kList.size && kList[idx].isNotEmpty()) {
                    p = Regex("\\b" + Regex.escape(key) + "\\b").replace(p ?: "") { kList[idx] }
                }
            }
            return p
        } catch (_: Exception) {
            return null
        }
    }

    private val mediaRegex =
        Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)

    /** Direct media links in a page body, including P.A.C.K.E.R.-packed evals. */
    private fun extractMediaLinks(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        mediaRegex.findAll(text).forEach { out.add(it.groupValues[1]) }
        try {
            val doc = org.jsoup.Jsoup.parse(text)
            for (s in doc.select("script")) {
                val content = s.data().ifBlank { s.html() }
                if (!content.contains("eval(")) continue
                val m = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(content) ?: continue
                val start = m.range.first
                val sample = if (content.length > start + 10000) content.substring(start, start + 10000) else content.substring(start)
                val unpacked = unpackPacker(sample) ?: continue
                mediaRegex.findAll(unpacked).forEach { out.add(it.groupValues[1]) }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * 3isk-style dig of one host embed page: fetch with a referer, extract
     * direct media (incl. packed evals), follow one nested iframe with the
     * embed as referer. Returns (links, code) where code is dig=N, cf,
     * disabled, nodirect or err.
     */
    private suspend fun digEmbed(embedUrl: String, referer: String): Pair<Set<String>, String> {
        val out = LinkedHashSet<String>()
        val pageHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml"
        )
        val r1 = runCatching { app.get(embedUrl, headers = pageHeaders, referer = referer) }.getOrNull() ?: return emptySet<String>() to "err"
        val t1 = runCatching { r1.text }.getOrNull() ?: return emptySet<String>() to "err"
        if (t1.contains("Just a moment", ignoreCase = true) || t1.contains("challenges.cloudflare.com")) return emptySet<String>() to "cf"
        if (t1.contains("Embeds disabled", ignoreCase = true)) return emptySet<String>() to "disabled"
        out += extractMediaLinks(t1)
        try {
            val doc = org.jsoup.Jsoup.parse(t1)
            val nested = doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }.firstOrNull { it.startsWith("http") }
            if (nested != null) {
                val r2 = runCatching { app.get(nested, headers = pageHeaders, referer = embedUrl) }.getOrNull()
                val t2 = r2?.let { runCatching { it.text }.getOrNull() }
                if (t2 != null && !t2.contains("Just a moment", ignoreCase = true) && !t2.contains("challenges.cloudflare.com") && !t2.contains("Embeds disabled", ignoreCase = true)) {
                    out += extractMediaLinks(t2)
                }
            }
        } catch (_: Exception) {
        }
        return out to if (out.isNotEmpty()) "dig=${out.size}" else "nodirect"
    }

    /**
     * v7: Faselhd-style WebView sniff, COROUTINE-FREE (our repo's CI
     * rejects the coroutines library). Runs the embed in an invisible 1x1
     * WebView on the UI thread and captures .m3u8 requests from the
     * network layer, with the WebView's cookies. Only for MiraVD/Mwdy
     * (the hosts plain HTTP cannot open). Bounded: 10s hard timeout,
     * 3s grace after page finished, always destroyed. The loadLinks
     * worker thread blocks on a latch (no coroutines). Returns
     * (links, code) where code is webview=N, webview-none, noactivity
     * or err.
     */
    private fun sniffWithWebView(embedUrl: String, referer: String): Pair<Set<String>, String> {
        val activity = appContext as? Activity ?: return emptySet<String>() to "noactivity"
        if (activity.isFinishing) return emptySet<String>() to "noactivity"
        val found = Collections.synchronizedSet(LinkedHashSet<String>())
        val latch = CountDownLatch(1)
        val cleaned = AtomicBoolean(false)
        var webViewRef: WebView? = null
        var dialogRef: Dialog? = null
        var decorRef: ViewGroup? = null
        var timeoutRunnable: Runnable? = null
        var graceRunnable: Runnable? = null
        val mainHandler = Handler(Looper.getMainLooper())
        // UI-thread only: remove callbacks, stop + destroy the WebView,
        // dismiss the dialog, detach from decor. Idempotent.
        fun cleanupOnUi() {
            if (!cleaned.compareAndSet(false, true)) return
            try { timeoutRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Exception) { }
            try { graceRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Exception) { }
            try { webViewRef?.stopLoading() } catch (_: Exception) { }
            try { webViewRef?.destroy() } catch (_: Exception) { }
            try { if (dialogRef?.isShowing == true) dialogRef?.dismiss() } catch (_: Exception) { }
            try { decorRef?.removeView(webViewRef) } catch (_: Exception) { }
        }
        // Safe from any thread: cleanup on UI thread, then release latch.
        fun finish() {
            try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    cleanupOnUi()
                    latch.countDown()
                } else {
                    activity.runOnUiThread { cleanupOnUi(); latch.countDown() }
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try {
            activity.runOnUiThread {
                try {
                    val webView = WebView(activity)
                    webViewRef = webView
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        blockNetworkImage = true
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                            try {
                                val u = request?.url?.toString() ?: ""
                                if (u.contains(".m3u8")) found.add(u)
                            } catch (_: Exception) { }
                            return super.shouldInterceptRequest(view, request)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            try {
                                graceRunnable?.let { mainHandler.removeCallbacks(it) }
                                graceRunnable = Runnable { finish() }
                                mainHandler.postDelayed(graceRunnable!!, 3000L)
                            } catch (_: Exception) { }
                        }
                    }
                    // Invisible host: 1x1 off-screen dialog, decor fallback.
                    try {
                        val dialog = Dialog(activity)
                        dialogRef = dialog
                        dialog.setCancelable(false)
                        dialog.setCanceledOnTouchOutside(false)
                        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                        dialog.window?.apply {
                            setBackgroundDrawableResource(android.R.color.transparent)
                            setDimAmount(0f)
                            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                            attributes = attributes?.apply {
                                width = 1; height = 1; x = -10000; y = -10000
                                gravity = Gravity.START or Gravity.TOP
                            }
                        }
                        dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                        dialog.show()
                    } catch (_: Exception) {
                        try {
                            val decor = activity.window?.decorView as? ViewGroup
                            decorRef = decor
                            decor?.addView(webView, ViewGroup.LayoutParams(1, 1))
                        } catch (_: Exception) { }
                    }
                    timeoutRunnable = Runnable { finish() }
                    mainHandler.postDelayed(timeoutRunnable!!, 10000L)
                    webView.loadUrl(embedUrl, mapOf("Referer" to referer))
                } catch (_: Exception) {
                    finish()
                }
            }
        } catch (_: Exception) {
            finish()
        }
        latch.await(12, TimeUnit.SECONDS)
        // Ensure cleanup even if the latch timed out without a finish.
        try { activity.runOnUiThread { cleanupOnUi() } } catch (_: Exception) { }
        return found to if (found.isNotEmpty()) "webview=${found.size}" else "webview-none"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v6: never die silently — if anything in the chain throws, the
        // failure becomes a visible DEBUG source instead of "no links".
        return try {
            loadLinksInner(data, isCasting, subtitleCallback, callback)
        } catch (e: Exception) {
            emitDebug(listOf("LOADLINKS_CRASH", e.javaClass.simpleName, (e.message ?: "nomsg").take(120)), callback)
            true
        }
    }

    private suspend fun loadLinksInner(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v2: dual-host API fallback + ranked server resolution +
        // structured diagnostics (API vs extractor vs Cloudflare block).
        val diag = mutableListOf<String>()
        // v3: record the exact data loadLinks received, so the next
        // on-device DEBUG line shows the requested path, not just host.
        diag += "data=$data"
        // v4: CloudStream resolves relative episode data against mainUrl, so
        // the phone passes "https://turkmoodapi.xyz/episode:<id>", not
        // "episode:<id>". Extract the digits after "episode:" wherever they
        // appear; anything else is used as-is (movie API URLs).
        val epId = data.substringAfter("episode:", "").trim().takeWhile { it.isDigit() }
        val isEpisode = epId.isNotEmpty()
        val primaryUrl = if (isEpisode) "$api/episodes/$epId" else data
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
        val startedAt = System.currentTimeMillis()
        var dug = 0
        var webviewAttempts = 0
        for ((_, _, entry) in ranked) {
            val (nm, link) = entry
            if (!seen.add(link)) continue
            val ok = runCatching { loadExtractor(link, detailUrl, subtitleCallback, callback) }.getOrNull() ?: false
            if (ok) {
                outcomes += "$nm:OK"
                found = true
                continue
            }
            // v8: MiraVD/Mwdy go STRAIGHT to WebView sniff — the plain
            // dig cannot open them (CF 403 / disabled embeds) and only
            // burns seconds before the sniff. Other hosts keep the dig.
            val host = hostOf(link)
            if ((host.contains("miravd") || host.contains("mwdy")) && webviewAttempts < 2) {
                webviewAttempts++
                val (wlinks, wcode) = runCatching { sniffWithWebView(link, detailUrl) }.getOrDefault(emptySet<String>() to "err")
                if (wlinks.isNotEmpty()) {
                    for (media in wlinks) {
                        val isHls = media.contains(".m3u8")
                        val cookies = runCatching { CookieManager.getInstance().getCookie(media) ?: CookieManager.getInstance().getCookie(link) ?: "" }.getOrDefault("")
                        runCatching {
                            callback(
                                newExtractorLink(
                                    name,
                                    nm,
                                    media,
                                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = link
                                    this.quality = Qualities.Unknown.value
                                    val hdrs = mutableMapOf(
                                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                                        "Referer" to link
                                    )
                                    if (cookies.isNotBlank()) hdrs["Cookie"] = cookies
                                    this.headers = hdrs
                                }
                            )
                        }
                    }
                    outcomes += "$nm:WEBVIEW(${wlinks.size})"
                    found = true
                } else {
                    outcomes += "$nm:FAIL+webview-$wcode"
                }
            } else if (dug < 4 && System.currentTimeMillis() - startedAt < 12000) {
                dug++
                val (links, code) = runCatching { digEmbed(link, detailUrl) }.getOrDefault(emptySet<String>() to "err")
                if (links.isNotEmpty()) {
                    for (media in links) {
                        val isHls = media.contains(".m3u8")
                        runCatching {
                            callback(
                                newExtractorLink(
                                    name,
                                    nm,
                                    media,
                                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = link
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                                        "Referer" to link
                                    )
                                }
                            )
                        }
                    }
                    outcomes += "$nm:DIG(${links.size})"
                    found = true
                } else {
                    outcomes += "$nm:FAIL+$code"
                }
            } else {
                outcomes += "$nm:FAIL"
            }
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
                    "DEBUG v8 | " + diag.joinToString(" | "),
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
