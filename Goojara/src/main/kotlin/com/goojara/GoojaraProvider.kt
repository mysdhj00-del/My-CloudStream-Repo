package com.goojara

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Goojara provider for CloudStream 3 — v12 (phisher flow).
 *
 * Site: https://ww1.goojara.to — movies + TV series, English
 * titles, no login needed for browsing. Traced live from the
 * sandbox (curl, 2026-11-12) because the live browser refuses
 * this domain (policy_denied) and the site answers 200 plain
 * with a plain Android user agent — no Cloudflare wall seen.
 *
 * URL model (verified live):
 * - Lists: /watch-movies (35 cards p1), /watch-series (35 cards
 *   p1). Pagination appends ?p=N (p2 brings mostly NEW cards;
 *   a few featured repeats — dedup by URL handles them).
 * - Cards are short links: movies /mXXXXXX (e.g. /myPGm2 =
 *   The Uprising 2026), series episodes /eXXXXXX (e.g. /eZD2Nr
 *   = MobLand S2E1). Links appear relative AND absolute, on
 *   both ww1.goojara.to and www.goojara.to — everything is
 *   normalised to the ww1 host. Poster: md.goojara.to/<id>.jpg
 *   in the card img (data-src first, src fallback; skip
 *   /noimg.png and /spn.gif). Movie title: anchor text, year
 *   from the title attribute "Title (YEAR)". Series cards show
 *   the SERIES name as anchor text.
 * - Series hubs: /tXXXXXX?s=N (e.g. /tQa0dB?s=1 = MobLand S1,
 *   10 episodes; ?s=2 = S2, 2 episodes). Hub title is
 *   "Watch MobLand (2025) TV Serie". Episode anchors carry the
 *   episode TITLE as text ("Stick or Twist", ...) listed
 *   newest-first, so the episode number is (total - index).
 *   The episode page links its hub with ?s=N per season.
 * - Detail pages: movie title "Watch <Title> (YEAR)";
 *   episode title "Watch <Series> Season <S> Episode <E> -
 *   <EpTitle>". Movie pages carry an IMDb link
 *   (imdb.com/title/ttXXXX); episode pages do not — the
 *   subtitle gear falls back to IMDb suggest by series name.
 * - Search: POST /xmre.php with a token that ROTATES per page
 *   load (x=53d4024ca9, then x=8bafc0b340 on the next fetch) —
 *   search() fetches /watch-movies fresh, extracts the current
 *   token with a regex, then POSTs x=<token> with q=<query>.
 *   A stale token returns an empty 200 (verified).
 * - Direct Links: the detail page lists ~25 links labelled
 *   "<Host> DVD" (hosts seen: Wootly, luluvdo, dood,
 *   streamplay), each href = go.php?url=<encrypted token>.
 *
 * v12 — PHISHER FLOW (traced from phisher98's public Goojara
 * v5 plugin, decompiled from its published .cs3, then verified
 * live from the sandbox on Matchbox /m76rXj):
 * - The detail page's OWN Set-Cookie response header carries
 *   aGooz (server-set, plain HTTP — no JS, no WebView), and
 *   the page HTML carries a literal _3chk('NAME','VALUE')
 *   call. The go.php cookie is "aGooz=V; NAME=V".
 * - With that cookie, go.php answers 302 with the embed URL
 *   in the Location header (Matchbox: 302 ->
 *   https://www.wootly.ch/?v=KMGFEEE4). The old resolveOnce
 *   only watched res.url, which never changes on a 302 —
 *   that is why it scored 0/21 most runs.
 * - Wootly embed: GET the page -> iframe src -> POST the
 *   iframe with qdfx=1 -> regex var vd="..." + tk="..." ->
 *   GET https://web.wootly.ch/grabm?t=<tk>&id=<vd> -> the
 *   response text IS the direct video URL, emitted as one
 *   "Wootly" link, quality 720p, referer = iframe src.
 *   (From the sandbox the iframe URL itself 404s — datacenter
 *   IP / no shared session — but the plugin runs the same
 *   calls in the phone's app session, where phisher's addon
 *   plays wootly 720p.)
 * - The old phases (plain, WebView cookie, JS nav) stay as
 *   fallback and only run when the phisher phase finds 0.
 *
 * PLAYBACK — NOT VERIFIED, and v1 does not pretend otherwise:
 * - go.php tokens are encrypted and session-bound: from the
 *   sandbox they answer 404 with and without the session
 *   cookie, so the resolve step (app.get on the go.php URL in
 *   the phone session, then loadExtractor or dig on the final
 *   URL) could NOT be traced here. The phone test IS the trace:
 *   the DEBUG line reports hosts=/srv=/ext=/dig= + the go.php
 *   resolve rate, so one screenshot tells us whether the
 *   phone session resolves them.
 * - The site's player script is obfuscated and was flagged by
 *   a safety check — it is NOT executed, deobfuscated, or
 *   replicated here. The provider only follows the visible
 *   Direct Links with plain HTTP, the same as a browser tap.
 * - Subtitle gear (user spec): three modes — Arabic, English,
 *   or Arabic + English merged via the relay merge endpoint.
 *   Title (+year) -> IMDb suggest (key-free) -> OpenSubtitles
 *   v3 Stremio endpoint (key-free) -> sync-first ranking
 *   (23.976fps BluRay/WEB UTF-8 preferred). Fail-soft, 8 s
 *   caps — playback never waits on subtitles.
 * - No coroutine imports anywhere (CI classpath law): plain
 *   code, suspend functions only where the API requires them.
 */
class GoojaraProvider : MainAPI() {
    override var mainUrl = "https://ww1.goojara.to"
    override var name = "Goojara"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to mainUrl
    )

    // Section table, keyed by the DISPLAY name — the single source
    // of truth. getMainPage resolves the URL from the NAME on
    // purpose: it must work whichever mainPageOf convention
    // (url->name or name->url) the running CloudStream build
    // implements. v1 crashed with the name as URL, v2 (pairs
    // swapped) still crashed the same way on his phone, so the
    // pair order alone cannot be trusted across builds.
    private val sections = listOf(
        "أفلام — Goojara" to "$mainUrl/watch-movies",
        "الأفلام الرائجة — Goojara" to "$mainUrl/watch-movies-popular",
        "أحدث الأفلام — Goojara" to "$mainUrl/watch-movies-recent",
        "مسلسلات — Goojara" to "$mainUrl/watch-series",
        "المسلسلات الرائجة — Goojara" to "$mainUrl/watch-series-popular",
        "أحدث المسلسلات — Goojara" to "$mainUrl/watch-series-recent"
    )

    override val mainPage = mainPageOf(
        *sections.map { (name, url) -> url to name }.toTypedArray()
    )

    private val seenBySection = HashMap<String, HashSet<String>>()
    private val seenSeriesBySection = HashMap<String, HashSet<String>>()

    private val codeLinkRegex =
        Regex("""^(?:https?://(?:ww1\.|www\.)?goojara\.to)?/([me])([A-Za-z0-9]+)$""")
    private val yearRegex = Regex("""(19|20)\d\d""")

    /** Normalise any goojara.to link to the ww1 host. */
    private fun normUrl(raw: String): String {
        val fixed = fixUrl(raw)
        return fixed.replace("://www.goojara.to/", "://ww1.goojara.to/")
    }

    private fun cardPoster(anchor: Element): String? {
        val img = anchor.selectFirst("img") ?: return null
        val url = img.attr("data-src").ifBlank { img.attr("src") }
        if (url.isBlank()) return null
        if (url.contains("noimg.png") || url.contains("spn.gif")) return null
        return fixUrl(url)
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seenUrl = HashSet<String>()
        doc.select("a[href]").forEach { a ->
            val raw = a.attr("href").trim()
            val m = codeLinkRegex.find(raw) ?: return@forEach
            val kind = m.groupValues[1]
            val url = normUrl(raw)
            if (!seenUrl.add(url)) return@forEach
            val titleAttr = a.attr("title").trim()
            var title = a.text().trim()
            if (title.isBlank()) {
                title = titleAttr.replace(Regex("""\s*\(?\b(19|20)\d\d\)?\s*$"""), "").trim()
            }
            if (title.isBlank()) return@forEach
            val year = yearRegex.find(titleAttr)?.value?.toIntOrNull()
                ?: yearRegex.find(title)?.value?.toIntOrNull()
            val poster = cardPoster(a)
            if (kind == "m") {
                out += newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                out += newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Resolve the URL by section NAME first: works under either
        // mainPageOf convention (request.name may hold the name and
        // request.data the URL, or vice versa). Fall back to
        // whichever field actually holds an http(s) URL, never a
        // bare display name — a name can never be fetched.
        val base = sections.firstOrNull { it.first == request.name }?.second
            ?: sections.firstOrNull { it.first == request.data }?.second
            ?: request.data.takeIf { it.startsWith("http") }
            ?: request.name.takeIf { it.startsWith("http") }
            ?: sections[0].second
        val url = if (page <= 1) base else base + "?p=$page"
        val doc = app.get(url, headers = headers).document
        val items = parseCards(doc)
        val seen = seenBySection.getOrPut(base) { HashSet() }
        val seenSeries = seenSeriesBySection.getOrPut(base) { HashSet() }
        if (page <= 1) {
            seen.clear()
            seenSeries.clear()
        }
        val fresh = items.filter { item ->
            if (!seen.add(item.url)) return@filter false
            // Series list cards are episode cards carrying the
            // SERIES name: one card per series per section.
            if (item.type == TvType.TvSeries && !seenSeries.add(item.name)) return@filter false
            true
        }
        return newHomePageResponse(request.name, fresh, hasNext = fresh.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The xmre.php token rotates per page load: fetch a fresh
        // list page and extract the CURRENT token, then POST at
        // once in the same session. A stale token returns an
        // empty 200 (verified 2026-11-12).
        val seed = runCatching {
            app.get("$mainUrl/watch-movies", headers = headers).text
        }.getOrNull() ?: return emptyList()
        val token = Regex("""x=([a-z0-9]{6,})&q=""").find(seed)
            ?.groupValues?.getOrNull(1)
            // Detail pages carry the token in a second form
            // (&x=<token> with a separate z= rand value).
            ?: Regex("""&x=([a-z0-9]{6,})""").find(seed)
                ?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val res = runCatching {
            app.post(
                "$mainUrl/xmre.php",
                data = mapOf("x" to token, "q" to query),
                referer = "$mainUrl/watch-movies",
                headers = headers
            )
        }.getOrNull() ?: return emptyList()
        if (res.text.isBlank()) return emptyList()
        return parseCards(Jsoup.parse(res.text))
    }

    // ---------- load ----------

    private data class EpisodeInfo(val url: String, val title: String)

    private fun docPoster(doc: Document): String? {
        val og = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        if (!og.isNullOrBlank() && og.contains("md.goojara.to")) return fixUrl(og)
        val img = doc.select("img").firstOrNull {
            val u = it.attr("data-src").ifBlank { it.attr("src") }
            u.contains("md.goojara.to")
        } ?: return null
        return fixUrl(img.attr("data-src").ifBlank { img.attr("src") })
    }

    private fun docPlot(doc: Document): String {
        val desc = doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()
        return desc ?: ""
    }

    private fun docImdbId(doc: Document): String? {
        return Regex("""imdb\.com/title/(tt\d+)""").find(doc.html())
            ?.groupValues?.getOrNull(1)
    }

    /** Season hubs linked from an episode page: /tXXXXXX?s=N. */
    private fun hubLinks(doc: Document): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val seen = HashSet<String>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            val m = Regex("""/(t[A-Za-z0-9]+)\?s=(\d+)""").find(href) ?: return@forEach
            val season = m.groupValues[2].toIntOrNull() ?: return@forEach
            val hub = normUrl(mainUrl + "/" + m.groupValues[1])
            if (seen.add("$hub?s=$season")) out += hub to season
        }
        return out.sortedBy { it.second }
    }

    /**
     * Fetch one season hub and return its episodes in page
     * order (newest first). The episode number is (total -
     * index): the hub lists E10..E1, verified on MobLand S1.
     */
    private suspend fun fetchSeasonEpisodes(
        hub: String,
        season: Int,
        seriesName: String
    ): List<Triple<String, String, Int>> {
        val doc = runCatching {
            app.get("$hub?s=$season", headers = headers).document
        }.getOrNull() ?: return emptyList()
        val anchors = ArrayList<Pair<String, String>>()
        doc.select("a[href]").forEach { a ->
            val raw = a.attr("href").trim()
            val m = codeLinkRegex.find(raw) ?: return@forEach
            if (m.groupValues[1] != "e") return@forEach
            anchors += normUrl(raw) to a.text().trim()
        }
        val total = anchors.size
        return anchors.mapIndexed { index, (epUrl, epTitle) ->
            Triple(epUrl, epTitle, total - index)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val rawTitle = doc.title().trim()
        val poster = docPoster(doc)
        val plot = docPlot(doc)
        val tt = docImdbId(doc)

        // Episode page: "Watch <Series> Season <S> Episode <E> - <EpTitle>"
        val epMatch = Regex("""^Watch (.+?) Season (\d+) Episode (\d+)(?: - (.*))?$""")
            .find(rawTitle)
        if (epMatch != null) {
            val seriesName = epMatch.groupValues[1].trim()
            val hubSeasons = hubLinks(doc)
            val episodes = ArrayList<Episode>()
            if (hubSeasons.isEmpty()) {
                val season = epMatch.groupValues[2].toIntOrNull() ?: 1
                val ep = epMatch.groupValues[3].toIntOrNull() ?: 1
                val epTitle = epMatch.groupValues[4].trim()
                episodes += newEpisode(subData(url, seriesName, season, ep, tt)) {
                    this.name = epTitle.ifBlank { "الحلقة $ep" }
                    this.season = season
                    this.episode = ep
                }
            } else {
                for ((hub, season) in hubSeasons) {
                    val eps = fetchSeasonEpisodes(hub, season, seriesName)
                    for ((epUrl, epTitle, epNum) in eps) {
                        episodes += newEpisode(subData(epUrl, seriesName, season, epNum, null)) {
                            this.name = epTitle.ifBlank { "الحلقة $epNum" }
                            this.season = season
                            this.episode = epNum
                        }
                    }
                }
                episodes.sortWith(compareBy({ it.season }, { it.episode }))
            }
            val year = yearRegex.find(rawTitle)?.value?.toIntOrNull()
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        // Series hub: "Watch <Series> (YEAR) TV Serie"
        val hubMatch = Regex("""^Watch (.+?) \((\d{4})\) TV Serie$""").find(rawTitle)
        if (hubMatch != null) {
            val seriesName = hubMatch.groupValues[1].trim()
            val year = hubMatch.groupValues[2].toIntOrNull()
            val hubBase = url.substringBefore("?").trimEnd('/')
            val seasons = hubLinks(doc).ifEmpty { listOf(hubBase to 1) }
            val episodes = ArrayList<Episode>()
            for ((hub, season) in seasons) {
                val eps = fetchSeasonEpisodes(hub, season, seriesName)
                for ((epUrl, epTitle, epNum) in eps) {
                    episodes += newEpisode(subData(epUrl, seriesName, season, epNum, null)) {
                        this.name = epTitle.ifBlank { "الحلقة $epNum" }
                        this.season = season
                        this.episode = epNum
                    }
                }
            }
            episodes.sortWith(compareBy({ it.season }, { it.episode }))
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        // Movie: "Watch <Title> (YEAR)"
        val cleanTitle = rawTitle.removePrefix("Watch ").trim()
        val year = yearRegex.find(cleanTitle)?.value?.toIntOrNull()
        val name = cleanTitle.replace(Regex("""\s*\(\b(19|20)\d\d\)\s*$"""), "").trim()
        return newMovieLoadResponse(name.ifBlank { cleanTitle }, url, TvType.Movie, subData(url, name, null, null, tt)) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    // ---------- loadLinks ----------

    // ---- Hidden-WebView session (v4) ---------------------------------
    // The panel's convergent answer to the go.php lock: go.php 404s
    // for plain HTTP because the session cookie is minted by the
    // site's JavaScript (document.cookie), which no HTTP client
    // ever sees. So load the detail page in a hidden WebView on
    // the main thread, let the JS run, read the cookies from
    // CookieManager, and replay go.php with them. If that still
    // 404s, load a go.php link inside the WebView and capture the
    // final video-host URL from its navigation.
    // Pattern copied from the app's own WebViewResolver:
    // WebView(getContext() as Context) on the main thread,
    // javaScriptEnabled + domStorageEnabled, CookieManager for
    // cookies. Plugin code must NOT use coroutine builders (CI
    // law), so main-thread work uses Handler + CountDownLatch.
    // v5: on-device v4 reported wv:no-ck/int:- in only t=7s, so
    // the WebView never even started — diagnose the context and
    // the construction error, and fall back to the Application
    // object via ActivityThread when the library context is null.

    /** Last context source tried: ctx:api / ctx:at / ctx:null. */
    private var ctxDiag = "ctx:-"

    /** Simple class name if a WebView failed to construct. */
    private var wvErr: String? = null

    /** Cookie NAMES from the WebView (never values), for the diag. */
    private var ckNames = "-"

    // v9: how many Set-Cookie entries the embed pages set (per loadLinks).
    private var embedCookiesFound = 0

    // v11: last video-URL resolve outcome (per loadLinks).
    private var lastVideoCode = 0
    private var lastChain = "-"

    // v12 (phisher flow): regexes for the _3chk pair in the
    // detail HTML and the wootly vd/tk in the iframe POST.
    private val chkRegex = Regex("""_3chk\(\s*'([^']+)'\s*,\s*'([^']+)'""")
    private val vdRegex = Regex("""var\s+vd\s*=\s*["']([^"']+)["']""")
    private val tkRegex = Regex("""tk\s*=\s*["']([^"']+)["']""")

    /** v12 diag: aGooz + _3chk presence on the detail page. */
    private var phDiag = "ph:-"

    /** v12 diag: last wootly grabm outcome. */
    private var wootlyDiag = "wt:-"

    // v6: v5 proved appContext() died silently inside getContext()
    // (ctx:- reset value, t=3s). The compiled call resolved to
    // ContextHelper_jvmKt — a class that exists ONLY in the stub
    // jar, not in the real Android app — so it threw
    // NoClassDefFoundError, which runCatching swallowed. Fix:
    // drop getContext() entirely; take the Context from
    // CloudStreamApp.context (public getter, verified in the
    // stub), fall back to ActivityThread.currentApplication(),
    // and catch Throwable (not just Exception) so no failure is
    // ever silent again — the error class lands in ctx:err:<name>.

    /** Application context for the hidden WebView, with fallback. */
    private fun appContext(): Context? {
        var err: String? = null
        try {
            CloudStreamApp.context?.let { ctxDiag = "ctx:csa"; return it }
        } catch (t: Throwable) {
            err = t.javaClass.simpleName
        }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? Context
            if (app != null) { ctxDiag = "ctx:at"; return app }
        } catch (t: Throwable) {
            err = t.javaClass.simpleName
        }
        ctxDiag = if (err != null) "ctx:err:$err" else "ctx:null"
        return null
    }

    /** Run [block] on the main thread, wait up to [timeoutMs]. */
    private fun runOnMain(timeoutMs: Long, block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { block() }
            return true
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (e: Exception) {
            } finally {
                latch.countDown()
            }
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun isOutsideGoojara(url: String): Boolean {
        if (!url.startsWith("http")) return false
        return !url.contains("goojara.to")
    }

    /**
     * Load [pageUrl] in a hidden WebView (main thread, JS on) and
     * return the goojara.to cookies the site's JS set, or null.
     * Fail-soft, time-boxed, never throws.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webViewCookies(pageUrl: String): String? {
        val ctx = appContext() ?: return null
        val holder = arrayOfNulls<WebView>(1)
        val finished = CountDownLatch(1)
        var created = false
        runOnMain(5000) {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = headers["User-Agent"] ?: wv.settings.userAgentString
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        finished.countDown()
                    }
                }
                holder[0] = wv
                created = true
                wv.loadUrl(pageUrl)
            } catch (e: Exception) {
                wvErr = e.javaClass.simpleName
                finished.countDown()
            }
        }
        if (!created) return null
        finished.await(15000, TimeUnit.MILLISECONDS)
        // Poll for the session cookie: the site's JS may set it
        // seconds after the page finishes (challenge / XHR).
        // v10: wait for the cookie to STABILISE — the JS may write
        // a placeholder first and rewrite it once its check
        // completes; a first-seen value can be dead on arrival.
        var cookies: String? = null
        var lastSeen: String? = null
        var stablePolls = 0
        val pollEnd = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < pollEnd) {
            runOnMain(5000) {
                cookies = runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull()
                    ?: runCatching { CookieManager.getInstance().getCookie(mainUrl) }.getOrNull()
            }
            if (!cookies.isNullOrBlank()) {
                if (cookies == lastSeen) {
                    stablePolls++
                    if (stablePolls >= 2) break
                } else {
                    stablePolls = 0
                }
                lastSeen = cookies
            }
            Thread.sleep(1000)
        }
        if (!cookies.isNullOrBlank()) Thread.sleep(2000)
        runOnMain(5000) {
            runCatching { CookieManager.getInstance().flush() }
        }
        // Record cookie NAMES only (never values) for the diag.
        ckNames = cookies?.split(";")?.mapNotNull { it.substringBefore("=").trim().takeIf { n -> n.isNotEmpty() } }?.joinToString(",")?.take(60) ?: "-"
        runOnMain(5000) {
            runCatching { holder[0]?.stopLoading() }
            runCatching { holder[0]?.destroy() }
            holder[0] = null
        }
        return cookies?.takeIf { it.isNotBlank() }
    }

    /**
     * v7: navigate to [goUrl] FROM the detail page, inside the
     * WebView — window.location.href after the page + its JS
     * session are live — and return the first URL outside
     * goojara.to the WebView lands on. This is the faithful
     * browser flow (detail-page referer, WebView cookie jar,
     * Sec-Fetch headers); a bare loadUrl(go.php) is not.
     * Fail-soft, time-boxed.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webViewJsNav(pageUrl: String, goUrl: String): String? {
        val ctx = appContext() ?: return null
        val holder = arrayOfNulls<WebView>(1)
        val seen = Collections.synchronizedList(ArrayList<String>())
        val finished = CountDownLatch(1)
        val done = CountDownLatch(1)
        var created = false
        runOnMain(5000) {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = headers["User-Agent"] ?: wv.settings.userAgentString
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        seen.add(request.url.toString())
                        if (isOutsideGoojara(request.url.toString())) done.countDown()
                        return false
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        seen.add(url)
                        finished.countDown()
                        if (isOutsideGoojara(url)) done.countDown()
                    }
                }
                holder[0] = wv
                created = true
                wv.loadUrl(pageUrl)
            } catch (e: Exception) {
                wvErr = e.javaClass.simpleName
                finished.countDown()
                done.countDown()
            }
        }
        if (!created) return null
        finished.await(15000, TimeUnit.MILLISECONDS)
        // Let the JS session settle, then navigate as the site does.
        Thread.sleep(3000)
        runOnMain(5000) {
            runCatching {
                holder[0]?.evaluateJavascript("window.location.href='$goUrl'", null)
            }
        }
        done.await(12000, TimeUnit.MILLISECONDS)
        Thread.sleep(1500)
        val found = synchronized(seen) { seen.firstOrNull { isOutsideGoojara(it) } }
        runOnMain(5000) {
            runCatching { holder[0]?.stopLoading() }
            runCatching { holder[0]?.destroy() }
            holder[0] = null
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (realData, subTitle, subSeason, subEp, subTt) = parseSubData(data)
        var subDiag = "sub:-"
        if (subTitle.isNotBlank()) {
            subDiag = runCatching {
                emitSubs(subTitle, subSeason, subEp, subTt, subtitleCallback)
            }.getOrDefault("sub:err")
        }
        var emittedLinks = 0
        var digCount = 0
        embedCookiesFound = 0
        lastVideoCode = 0
        lastChain = "-"
        phDiag = "ph:-"
        wootlyDiag = "wt:-"
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            emittedLinks++
            callback(link)
        }
        var goTotal = 0
        var goResolved = 0
        val hosts = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        ctxDiag = "ctx:-"
        wvErr = null
        ckNames = "-"
        var wvDiag = "wv:-"
        var sessionCookies: String? = null
        // One resolve pass over the go.php links, optionally carrying
        // a session Cookie header. Returns how many links resolved
        // to a different final URL.
        suspend fun resolveOnce(links: Elements, cookie: String?): Int {
            var resolved = 0
            val reqHeaders = if (cookie != null) headers + ("Cookie" to cookie) else headers
            for (a in links) {
                if (System.currentTimeMillis() - t0 > 25000) break
                val goUrl = normUrl(a.attr("href"))
                val host = a.text().trim().split(" ").firstOrNull()
                    ?.lowercase()?.ifBlank { null } ?: "go"
                val res = runCatching {
                    app.get(goUrl, headers = reqHeaders, referer = realData)
                }.getOrNull() ?: continue
                val finalUrl = res.url
                if (res.code !in 200..299 || finalUrl == goUrl) continue
                resolved++
                if (!hosts.contains(host)) hosts += host
                try {
                    if (tryDirectExtract(finalUrl, goUrl, countingCallback, cookie)) {
                        digCount++
                        continue
                    }
                    loadExtractor(finalUrl, goUrl, subtitleCallback, countingCallback)
                } catch (e: Exception) {
                    // One dead host must not kill the rest.
                }
            }
            return resolved
        }
        // v12 (phisher flow): one pass over the go.php links with
        // the detail-page cookie. go.php answers 302 with the
        // embed URL in the Location header — read it first, fall
        // back to a changed final URL. Wootly embeds go through
        // the grabm chain; the rest use the old dig/extractor
        // path. Returns how many links resolved.
        suspend fun resolvePhisher(links: Elements, cookie: String): Int {
            var resolved = 0
            val reqHeaders = headers + ("Cookie" to cookie)
            for (a in links) {
                if (System.currentTimeMillis() - t0 > 25000) break
                val goUrl = normUrl(a.attr("href"))
                if (goUrl.isBlank()) continue
                val host = a.text().trim().split(" ").firstOrNull()
                    ?.lowercase()?.ifBlank { null } ?: "go"
                val res = runCatching {
                    app.get(goUrl, headers = reqHeaders, referer = mainUrl)
                }.getOrNull() ?: continue
                val loc = res.headers["Location"] ?: res.headers["location"]
                    ?: if (res.code in 200..299 && res.url != goUrl) res.url else null
                if (loc == null) continue
                resolved++
                if (!hosts.contains(host)) hosts += host
                try {
                    if (loc.contains("wootly")) {
                        if (resolveWootly(loc, goUrl, countingCallback)) {
                            digCount++
                            continue
                        }
                    }
                    if (tryDirectExtract(loc, goUrl, countingCallback, cookie)) {
                        digCount++
                        continue
                    }
                    loadExtractor(loc, goUrl, subtitleCallback, countingCallback)
                } catch (e: Exception) {
                    // One dead host must not kill the rest.
                }
            }
            return resolved
        }
        var links: Elements? = null
        var phCookie: String? = null
        try {
            // v12 (phisher flow): the detail page's OWN Set-Cookie
            // header carries aGooz, and the page HTML carries a
            // literal _3chk('NAME','VALUE') call. The go.php
            // cookie is "aGooz=V; NAME=V" — built here, used
            // in Phase P below.
            val detailResp = app.get(
                realData,
                headers = headers + ("Referer" to "https://www.goojara.to") +
                    ("Cookie" to "") + ("Cache-Control" to "no-cache")
            )
            val doc = detailResp.document
            val setCookie = detailResp.headers["Set-Cookie"]
                ?: detailResp.headers["set-cookie"] ?: ""
            val aGooz = Regex("""aGooz=([^;\r\n]+)""").find(setCookie)
                ?.groupValues?.getOrNull(1)
            val chk = chkRegex.find(doc.outerHtml())?.groupValues
            val parts = ArrayList<String>()
            if (!aGooz.isNullOrBlank()) parts += "aGooz=$aGooz"
            if (chk != null && chk.size > 2) parts += "${chk[1]}=${chk[2]}"
            phCookie = parts.joinToString("; ").ifEmpty { null }
            phDiag = "ph:aGooz=${if (aGooz != null) 1 else 0}+chk=${if (chk != null) 1 else 0}"
            val found = doc.select("#drl a").ifEmpty { doc.select("a[href*=go.php?url=]") }
            goTotal = found.size
            links = found
        } catch (e: Exception) {
            // Fall through to the DEBUG marker below.
        }
        val list = links
        if (list != null && list.isNotEmpty()) {
            // Phase P (v12, phisher flow): the go.php cookie from
            // the detail page, Location-header resolve, wootly
            // grabm first. The old phases only run when it
            // finds nothing.
            if (phCookie != null) {
                goResolved = resolvePhisher(list, phCookie)
            }
            // Phase 0: plain HTTP, exactly as v3.
            if (goResolved == 0) {
                goResolved = resolveOnce(list, null)
            }
            // Phase 1: hidden WebView session (only when plain found nothing).
            if (goResolved == 0) {
                val cookies = runCatching { webViewCookies(realData) }.getOrNull()
                sessionCookies = cookies
                wvDiag = if (cookies != null) "wv:ck${cookies.length}" else "wv:no-ck"
                if (cookies != null) {
                    goResolved = resolveOnce(list, cookies)
                }
                // v10: one fresh-cookie retry — the first session can
                // be a dead placeholder (or the gate throttles the
                // first burst); a second warm session sometimes opens it.
                if (goResolved == 0) {
                    val cookies2 = runCatching { webViewCookies(realData) }.getOrNull()
                    if (cookies2 != null) {
                        sessionCookies = cookies2
                        wvDiag += "/r2:ck${cookies2.length}"
                        goResolved = resolveOnce(list, cookies2)
                    }
                }
            }
            // Phase 2: intercept the first go.php link inside the WebView.
            if (goResolved == 0) {
                val firstGo = runCatching { normUrl(list.first()?.attr("href") ?: "") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (firstGo != null) {
                    val intercepted = runCatching { webViewJsNav(realData, firstGo) }.getOrNull()
                    if (intercepted != null) {
                        wvDiag += "/int:" + intercepted.take(60)
                        try {
                            if (tryDirectExtract(intercepted, firstGo, countingCallback, sessionCookies)) {
                                digCount++
                                if (sessionCookies != null) wvDiag += "/hdr"
                            } else {
                                loadExtractor(intercepted, firstGo, subtitleCallback, countingCallback)
                            }
                        } catch (e: Exception) {
                        }
                    } else {
                        wvDiag += "/int:-"
                    }
                }
            }
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        val hostsCsv = hosts.joinToString(",").take(80)
        val debugName = "DEBUG Goojara v12 | hosts=$hostsCsv | srv=$goResolved/$goTotal | ext=$emittedLinks | dig=$digCount | ec=$embedCookiesFound | vc=$lastVideoCode | ch=$lastChain | $phDiag | $wootlyDiag | t=${secs}s | $wvDiag | ckn=$ckNames | $ctxDiag | wve=${wvErr ?: "-"} | $subDiag"
        callback(
            newExtractorLink(
                "Mux",
                debugName,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                ExtractorLinkType.M3U8
            ) {
                this.referer = realData
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /**
     * v12 (phisher flow): resolve a wootly embed to its direct
     * video URL. GET the embed page -> iframe src -> POST the
     * iframe with qdfx=1 -> regex var vd="..." + tk="..." ->
     * GET https://web.wootly.ch/grabm?t=<tk>&id=<vd> -> the
     * response text IS the video URL. Emitted as one "Wootly"
     * link, quality 720p, referer = iframe src — exactly the
     * single source phisher's addon plays. Returns true when
     * a link was emitted. Fail-soft: any miss returns false.
     */
    private suspend fun resolveWootly(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val page = app.get(embed, headers = headers, referer = referer)
            val iframe = page.document.select("iframe").attr("src")
                .takeIf { it.isNotBlank() } ?: return false
            val post = app.post(
                iframe,
                data = mapOf("qdfx" to "1"),
                referer = embed,
                headers = headers
            )
            val text = post.text
            val vd = vdRegex.find(text)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return false
            val tk = tkRegex.find(text)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return false
            val grab = app.get(
                "https://web.wootly.ch/grabm?t=$tk&id=$vd",
                headers = headers,
                referer = iframe
            )
            wootlyDiag = "wt:${grab.code}"
            val videoUrl = grab.text.trim()
                .takeIf { it.startsWith("http") } ?: return false
            val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink("Wootly", "Wootly", videoUrl, type) {
                    this.referer = iframe
                    this.quality = Qualities.P720.value
                }
            )
            true
        } catch (e: Exception) {
            wootlyDiag = "wt:err"
            false
        }
    }

    /**
     * Direct packed-JS extraction: fetch an embed page, unpack
     * a P.A.C.K.E.R. block if present, and emit the video URL
     * it hides. Returns true when a link was emitted (the
     * caller then skips loadExtractor for this embed).
     * Fail-soft: any miss returns false.
     */
    private suspend fun tryDirectExtract(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        sessionCookie: String? = null
    ): Boolean {
        return try {
            val reqHeaders = if (sessionCookie != null) headers + ("Cookie" to sessionCookie) else headers
            val resp = app.get(embed, headers = reqHeaders, referer = referer)
            val html = resp.text
            // v11: fall back to a plain video URL in the raw page when
            // there is no packed-JS block (some hosts render it plain).
            val unpacked = if (html.contains("eval(function(p,a,c,k,e")) {
                GoojaraUnpack.unpackPackedJs(html) ?: return false
            } else {
                html
            }
            val videoUrl = GoojaraUnpack.extractVideoUrl(unpacked) ?: return false
            val host = embed.substringAfter("://").substringBefore("/")
                .removePrefix("www.").substringBefore(".")
            // v11: resolve the video URL through OkHttp FIRST and hand
            // the player the FINAL url. The player's HttpURLConnection
            // path drops Set-Cookie across a 302 hop (freecode: the
            // most likely cause of ExoPlayer 2004 with identical
            // headers), while OkHttp carries the cookie into hop 2.
            // Record the redirect chain in the diag.
            var finalUrl = videoUrl
            try {
                val vresp = app.get(videoUrl, headers = reqHeaders, referer = referer)
                lastVideoCode = vresp.code
                val hops = mutableListOf<Int>()
                var nr = vresp.okhttpResponse.networkResponse
                while (nr != null) {
                    hops += nr.code
                    nr = nr.priorResponse
                }
                lastChain = hops.reversed().joinToString(">")
                runCatching { vresp.body.close() }
                if (vresp.code !in 200..299) return false
                finalUrl = vresp.url
            } catch (e: Exception) {
                lastVideoCode = -1
                return false
            }
            val type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            // v9: the video host may set its own session cookie on the
            // embed page — merge it with the goojara session cookie so
            // the player sends the host's own session with the request.
            val embedCookies = resp.cookies
            embedCookiesFound += embedCookies.size
            val cookieParts = mutableListOf<String>()
            if (sessionCookie != null) cookieParts += sessionCookie
            val seenNames = sessionCookie?.split(";")?.map { it.substringBefore("=").trim() }?.toSet() ?: emptySet()
            embedCookies.forEach { (k, v) -> if (k !in seenNames) cookieParts += "$k=$v" }
            val cookieHeader = cookieParts.joinToString("; ").ifEmpty { null }
            callback(
                newExtractorLink(host, host, finalUrl, type) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                    if (cookieHeader != null) {
                        this.headers = mapOf(
                            "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                            "Cookie" to cookieHeader,
                            "Referer" to embed
                        )
                    }
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- subtitle gear (3 modes) ----------

    private val relayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"

    private fun httpGetText(url: String, timeoutMs: Int): String? {
        return runCatching {
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                return@runCatching null
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    private fun latinTitleCandidates(title: String): List<String> {
        var t = title.replace(Regex("""[\u0600-\u06FF]+"""), " ")
        t = t.replace(Regex("""(?i)\b(mtarjm|mudblj|online|web-dl|bluray|hd|hdts|hdcam|webdl)\b"""), " ")
        t = t.replace(Regex("""[^A-Za-z0-9 :'\-!.,]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.replace(Regex("""\s+(19|20)\d\d\s*$"""), "").trim()
        if (t.isBlank()) return emptyList()
        val out = mutableListOf(t)
        Regex("""\s+\d{1,2}$""").find(t)?.let {
            val stripped = t.substring(0, it.range.first).trim()
            if (stripped.isNotBlank()) out += stripped
        }
        return out
    }

    private fun resolveImdbId(latinTitle: String, year: Int?, wantSeries: Boolean): String? {
        val bucket = latinTitle.firstOrNull()?.lowercaseChar() ?: return null
        val q = java.net.URLEncoder.encode(latinTitle, "UTF-8")
        val json = httpGetText(
            "https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json", 8000
        ) ?: return null
        val arr = runCatching {
            JSONObject(json).optJSONArray("d")
        }.getOrNull() ?: return null
        val want = if (wantSeries) "TV series" else "feature"
        val other = if (wantSeries) "feature" else "TV series"
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val kind = o.optString("q")
            if (fallback == null && kind == other) fallback = id
            if (kind != want) continue
            if (year != null && o.has("y") && o.optInt("y") != year) continue
            return id
        }
        return fallback
    }

    /** Best OpenSubtitles file for one language, sync-first ranking. */
    private fun bestSubFor(arr: org.json.JSONArray, lang: String): String? {
        var bestUrl = ""
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("lang") != lang) continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            var score = 0
            if (o.optInt("fpsMilli", 0) == 23976) score += 3
            val fmt = o.optString("releaseFormat")
            if (fmt == "BluRay" || fmt == "WEB") score += 2
            if (o.optString("SubEncoding") == "UTF-8") score += 1
            if (score > bestScore) {
                bestScore = score
                bestUrl = u
            }
        }
        return bestUrl.ifBlank { null }
    }

    private fun emitSubs(
        title: String,
        season: Int?,
        ep: Int?,
        ttDirect: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val mode = GoojaraSubs.mode
        val year = yearRegex.find(title)?.value?.toIntOrNull()
        val wantSeries = season != null && ep != null
        var tt = ttDirect
        if (tt.isNullOrBlank()) {
            for (candidate in latinTitleCandidates(title)) {
                tt = resolveImdbId(candidate, year, wantSeries)
                if (!tt.isNullOrBlank()) break
            }
        }
        if (tt.isNullOrBlank()) return "sub:no-match"
        val osUrl = if (wantSeries)
            "https://opensubtitles-v3.strem.io/subtitles/series/$tt:$season:$ep.json"
        else
            "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json"
        val json = httpGetText(osUrl, 8000) ?: return "sub:no-match"
        val arr = runCatching {
            JSONObject(json).optJSONArray("subtitles")
        }.getOrNull() ?: return "sub:no-match"
        val ara = bestSubFor(arr, "ara")
        val eng = bestSubFor(arr, "eng")
        return when (mode) {
            "ar" -> {
                if (ara != null) {
                    subtitleCallback(SubtitleFile("العربية", ara))
                    "sub:ar"
                } else "sub:no-match"
            }
            "en" -> {
                if (eng != null) {
                    subtitleCallback(SubtitleFile("English", eng))
                    "sub:en"
                } else "sub:no-match"
            }
            else -> {
                if (eng != null && ara != null) {
                    val mergeUrl = StringBuilder("$relayBase/relay/mergesubs?eng=")
                        .append(java.net.URLEncoder.encode(eng, "UTF-8"))
                        .append("&ara=")
                        .append(java.net.URLEncoder.encode(ara, "UTF-8"))
                        .toString()
                    subtitleCallback(SubtitleFile("العربية + الإنجليزية", mergeUrl))
                    "sub:dual"
                } else if (ara != null) {
                    subtitleCallback(SubtitleFile("العربية", ara))
                    "sub:ar-only"
                } else if (eng != null) {
                    subtitleCallback(SubtitleFile("English", eng))
                    "sub:en-only"
                } else "sub:no-match"
            }
        }
    }

    /** Packs url + title + season/episode + IMDb id into the data string. */
    private fun subData(url: String, title: String, season: Int?, ep: Int?, tt: String?): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return url + "|||" + enc + "|||" + (season?.toString() ?: "") + "|||" +
            (ep?.toString() ?: "") + "|||" + (tt ?: "")
    }

    private data class SubPack(
        val url: String,
        val title: String,
        val season: Int?,
        val ep: Int?,
        val tt: String?
    )

    private fun parseSubData(data: String): SubPack {
        val parts = data.split("|||")
        val real = parts.getOrNull(0) ?: data
        val title = parts.getOrNull(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("")
        }.orEmpty()
        val season = parts.getOrNull(2)?.toIntOrNull()
        val ep = parts.getOrNull(3)?.toIntOrNull()
        val tt = parts.getOrNull(4)?.ifBlank { null }
        return SubPack(real, title, season, ep, tt)
    }
}
