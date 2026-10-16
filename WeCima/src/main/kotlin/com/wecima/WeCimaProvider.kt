package com.wecima

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
 * WeCima provider for CloudStream 3.
 *
 * Built against the live structure of https://wecima.gripe verified on 2026-10-15:
 * - WordPress (MyCima-family theme). Cards are .GridItem blocks: the
 *   .Thumb--GridItem anchor carries href + title; the poster hides in a
 *   span's data-lazy-style CSS variable.
 * - Movies and episodes are root-level posts (Arabic slugs); series hubs at
 *   /series/{slug}/ list every episode post directly (the /season/{id}
 *   links the theme prints are dead — 404 — and are ignored).
 * - Archives paginate WordPress-style: {base}page/{n}/. Search is /?s={q}.
 * - The post page renders its watch servers as li[data-watch] inside
 *   .WatchArea (plus a download list of token links). On this domain each
 *   post carries a single proprietary player host (akhbarworld.online).
 *   v1 scanned its page for a direct .m3u8/.mp4 and missed on-device
 *   (page=200 | servers=1 | scan=miss): the host is an ArtPlayer page that
 *   carries the stream as a JS constant — const videoUrl = "..." — either
 *   an escaped-slash HLS master or an extensionless redirector URL from
 *   which the player derives v.mp4 quality variants. v2 extracts exactly
 *   that constant (with a retry; the host is flaky), falling back to the
 *   generic extension sweep. DEBUG markers: page/servers/vu/scan.
 * - v3 (2026-10-15): v2's readout on the user's phone was an ExoPlayer
 *   ERROR_CODE_IO_BAD_HTTP_STATUS on the emitted redirector URL — the
 *   phone DID receive the real stream URL, but the redirector (a
 *   Cloudflare Worker) answered the player's plain fetch with an error
 *   status (the same URL returns HTTP 500 "error code: 1101" from
 *   datacenter egresses in every header variant, while the site's own
 *   browser player plays it). v3 therefore (a) probes the stream URL
 *   from inside loadLinks with a full browser media-fetch header set,
 *   recording probe=<code> and the final URL after redirects, (b) emits
 *   the probed final URL when it differs, and (c) arms every emitted
 *   stream link with the same browser header set so the player presents
 *   itself like the site's own player.
 * - v4 (2026-10-15): v3 changed nothing on-device, and its probe result
 *   never reached the user (the DEBUG source only surfaces when nothing
 *   is found — a design flaw). v4 probes the stream URL with THREE
 *   header profiles (mobile-browser media fetch, desktop-browser media
 *   fetch, minimal), records every code (p1/p2/p3), emits with the first
 *   profile the server accepts, and ALWAYS surfaces the DEBUG source so
 *   the markers are readable in the source list either way.
 * - v5 (2026-10-15): the v4 readout also exposed a SECOND player shape
 *   the scans kept missing (a movie post read scan=miss on-device): some
 *   posts wrap the stream as ?vfxcimavd=<base64> and the wrapper page is
 *   an hls.js player whose stream constant is named videoSrc and points
 *   at an HLS playlist disguised as master.txt (segments are .txt too)
 *   on a rotating host. v5 extracts videoSrc, emits it as M3U8, and adds
 *   master.txt to the generic sweep. The ?my_player= shape's redirector
 *   (link.mycima.cv) still answers 500 to every client profile — that is
 *   the operator's broken Worker, outside provider control.
 * - v6 (2026-10-16): dual-domain. The user supplied the operator's backup
 *   domain, wecima.tattoo — same WordPress install and catalog (home
 *   verified: 200, identical .GridItem cards), but its inner pages sit
 *   behind a Cloudflare check that refuses datacenter egresses (403
 *   "Just a moment" from the sandbox on posts/search, home open). Every
 *   page fetch now tries tattoo first, then falls back to gripe, via
 *   fetchPage (a fetch counts as real only at HTTP 200, >20 KB, and no
 *   challenge marker); the DEBUG line records via=<host>. If tattoo
 *   serves the phone and its player chain differs from gripe's dead
 *   Worker, series episodes come back to life; if not, gripe keeps
 *   today's exact behavior (shape-B streams play).
 * - v7 (2026-10-16): the generic scan gained the JW file: pattern and a
 *   packed-JS unpack fallback (repo-wide hardening round).
 * - v8 (2026-10-16): REVERT to gripe-primary. On-device, a tattoo-only
 *   title (Silo S1E10) died at load() with "No title": tattoo's inner
 *   pages refuse the app's fetches (Cloudflare check — only a real
 *   browser passes), and the gripe fallback 404s on tattoo-only posts,
 *   so the dual-domain v6 design dead-ended every tattoo-only title at
 *   an error screen. mainUrl is gripe again (altUrl kept, now equal);
 *   tattoo remains watchable in the phone's browser, where it plays.
 * - v9 (2026-10-16): all THREE operator domains in one provider, per
 *   Mohamed's instruction. wecima.mobile (a custom theme, plain-HTTP
 *   fetchable — no Cloudflare wall) becomes the catalog + playback
 *   primary: it UNIFIES both catalogs (gripe's Arabic series and
 *   tattoo's foreign subtitled), and its /watch/{slug} pages run a
 *   resolve protocol over 12-13 standard extractor hosts (chain traced
 *   end-to-end live before writing this; see loadLinksMobile). gripe
 *   and tattoo stay as slug-mapped fallbacks for post pages, served by
 *   the WP chains below (gripe's akhbarworld shapes; tattoo activates
 *   only if its check ever lets the app through).
 * - v10 (2026-10-16): WP-domain items (old history entries) whose slug
 *   also exists on mobile are redirected to the mobile chain inside
 *   loadLinks — a gripe entry for أحلى نصيب ep1 otherwise still runs
 *   into the operator-dead Worker (p1/p2/p3=500) while the same episode
 *   has 13 working servers on mobile.
 * - v11 (2026-10-16): the mobile chain RAN on-device but its watch-page
 *   GET threw at transport level ("watch=err": no HTTP code, while curl
 *   and the phone's own browser get 200 — the host is slow: a 17 s page
 *   load was timed from the sandbox). A server-side relay is NOT an
 *   escape here: the operator 403s Render's fetches of wecima.mobile
 *   too (verified live via /relay/wecima: diag watch=403), the same
 *   block it gives Render for akhbarworld. So v11 hardens the local
 *   chain: the watch GET retries 3x with backoff, each resolve POST
 *   retries once, and the DEBUG line records the exception class
 *   (werr=) so a failure names itself (timeout vs TLS vs DNS).
 * - v12 (2026-10-16): the werr marker named it — IllegalArgumentException,
 *   thrown LOCALLY before any network I/O: the site's anchors carry RAW
 *   Arabic slugs, the episode's data URL went into the watch GET's
 *   referer header unencoded, and okhttp rejects non-ASCII header
 *   values. (Browsing worked because okhttp encodes the request-line
 *   URL itself; only headers are strict.) Fix: pctEncodeNonAscii at the
 *   loadLinksMobile choke point — data, watchUrl and every referer in
 *   the chain derive from the encoded form. The v11 retries stay as
 *   insurance for the host's genuine slowness.
 * - v12 CONFIRMED WORKING on-device (2026-10-16, "اشتغل").
 * - v13 (2026-10-16, Mohamed: "ياخذ وقت هل يمكنك جعله يفتح بسرعة"):
 *   speed pass on measured timings (warm session: watch ~2.3 s, resolve
 *   POST 0.3-0.8 s, fragment ~0.3 s unfollowed). Phase 1 now resolves
 *   ALL servers in parallel on a 5-thread raw-okhttp pool (NiceHttp is
 *   suspend-only, so pool threads use synchronous okhttp), fragments
 *   are read with redirects DISABLED (Location = the embed; v12
 *   followed each redirect and downloaded every embed page — the main
 *   cost), and a fully-failed round re-warms the watch session once.
 *   DEBUG gains p1ms= (phase-1 wall time) and fails= markers.
 *   (Publish incident: the first v13 artifact silently shipped the v12
 *   code — caught by the parent agent inspecting the live .cs3's dex
 *   for the p1ms= marker; republished as v14 with the real code.)
 * - v15 (2026-10-16, Mohamed: a film still took ~22 s to open): two
 *   more cuts. (1) PREFETCH: opening a title page starts resolving its
 *   servers on a background thread (raw okhttp; playCache, 10-min TTL)
 *   so the play press goes straight to the extractors. (2) ORDER: the
 *   extractor stage tries the usually-fastest hosts first (MixDrop,
 *   Doodstream, Voe, StreamTape...) — a stable sort, every server is
 *   still tried. DEBUG gains cache=hit.
 * - v16 (2026-10-16, Mohamed: "الكتالوج لايوجد" — section names render
 *   but every row is empty on-device, while the site serves 62 cards
 *   to the sandbox in ~1 s): getMainPage retries once on an empty
 *   parse and, if still empty, returns ONE diagnostic card naming the
 *   HTTP code / document length / serving host, so the row reports
 *   what the phone actually received; search does the same. Nothing
 *   changes when fetches succeed.
 * - v17 (2026-10-16): the DEBUG card spoke — code=403 in EVERY row:
 *   the site now refuses the app's catalog fetches from the user's IP
 *   (his browser and the sandbox still get 200; playback had worked
 *   at 12:09 and the block appeared after an hour of heavy test
 *   traffic, v15's prefetch storms included — volume is the prime
 *   suspect). Quiet-mode response: the prefetch call is REMOVED,
 *   getMainPage fetches exactly once per section, and fetchPage now
 *   carries per-host session cookies across requests (browser-like
 *   continuity). The DEBUG card stays — it will show the code
 *   recovering (403 -> 200) if/when the block cools down.
 * - The block LIFTED (2026-10-16 13:11: full poster catalog on-device).
 * - v18 (2026-10-16): a film tap at 13:10 (the cooldown's tail) still
 *   died with "No title" — the post-page fetch came back stripped
 *   while the page itself is complete (verified from the sandbox:
 *   og:title present). loadMobile now retries ONCE after 2 s when the
 *   title is blank, falls back to a slug-derived title, and falls back
 *   to the first <img> for the poster.
 * - v19 (2026-10-16): the 403 block is INTERMITTENT, not a one-off —
 *   the catalog rendered fully at 13:11 and was 403 again at 13:18
 *   under light use. The app bursts all section fetches at once; the
 *   site punishes bursts. v19 adds a provider-wide pacing gate
 *   (request starts >= 400 ms apart: fetchPage + resolveOneMobile),
 *   cuts the phase-1 pool 5 -> 3, and guards the slug-title fallback
 *   to post URLs only (a tapped DEBUG card had produced a junk title
 *   page named "https://wecima.mobile"). Poster image loads are the
 *   app's own and cannot be paced from here.
 * - Every request carries an Android-Chrome header set.
 */
class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.gripe"
    override var name = "WeCima"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
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

    // The operator's original domain; fallback when tattoo refuses.
    private val altUrl = "https://wecima.tattoo"

    // The operator's third domain (v9): a custom theme with a UNIFIED
    // catalog (the gripe Arabic series AND the tattoo foreign subtitled
    // catalog in one place), plain-HTTP fetchable, whose watch pages run
    // a resolve protocol (POST /watch/resolve {key, index} -> /m/ fragment
    // -> meta-refresh to the real host embed) over 12-13 standard servers.
    // It is the catalog + playback primary; gripe/tattoo remain as
    // slug-mapped fallbacks for post pages.
    private val gripeUrl = "https://wecima.gripe"
    private val mobileUrl = "https://wecima.mobile"

    private class Fetched(val document: Document, val code: Int, val host: String)

    /** Per-host session cookies, carried across fetches (v17): the
     * site's filter appears to favor requests with session continuity
     * (a browser always has it; stateless one-shot requests get 403). */
    private val hostCookies = mutableMapOf<String, Map<String, String>>()

    private suspend fun fetchPage(url: String): Fetched {
        // Candidate chain across the operator's three domains: a mobile
        // post maps to the same slug on the two WordPress domains; a WP
        // post swaps between the WP hosts; anything else is fetched as-is.
        val candidates = mutableListOf<String>()
        candidates += url
        val mobilePost = Regex("""^https://wecima\.mobile/(?:episode|film)/(.+)$""").find(url)
        if (mobilePost != null) {
            val slug = mobilePost.groupValues[1].trimEnd('/')
            candidates += "$gripeUrl/$slug/"
            candidates += "$altUrl/$slug/"
        } else if (url.startsWith(mainUrl)) {
            candidates += altUrl + url.removePrefix(mainUrl)
        } else if (url.startsWith(altUrl)) {
            candidates += mainUrl + url.removePrefix(altUrl)
        }
        var firstBad: Fetched? = null
        for (candidate in candidates) {
            val host = when {
                candidate.startsWith(mobileUrl) -> mobileUrl
                candidate.startsWith(altUrl) -> altUrl
                else -> mainUrl
            }
            awaitPace()
            val res = runCatching {
                app.get(candidate, headers = browserHeaders, cookies = hostCookies[host].orEmpty())
            }.getOrNull() ?: continue
            runCatching { res.cookies }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                hostCookies[host] = it
            }
            val doc = runCatching { res.document }.getOrNull() ?: continue
            val html = runCatching { doc.html() }.getOrDefault("")
            val real = res.code == 200 && html.length > 20000 &&
                !html.contains("Just a moment")
            val fetched = Fetched(doc, res.code, host)
            if (real) return fetched
            if (firstBad == null) firstBad = fetched
        }
        if (firstBad != null) return firstBad
        val res = app.get(url, headers = browserHeaders)
        return Fetched(res.document, res.code, mainUrl)
    }

    // Sections mirror wecima.mobile's own category menu (v9).
    override val mainPage = mainPageOf(
        "$mobileUrl/" to "المضاف حديثا",
        "$mobileUrl/category/افلام-اجنبي" to "أفلام أجنبية",
        "$mobileUrl/category/افلام-عربي" to "أفلام عربية",
        "$mobileUrl/category/افلام-هندي" to "أفلام هندية",
        "$mobileUrl/category/افلام-تركية" to "أفلام تركية",
        "$mobileUrl/category/افلام-اسيوية" to "أفلام آسيوية",
        "$mobileUrl/category/افلام-انمي" to "أفلام أنمي",
        "$mobileUrl/category/مسلسلات-عربي" to "مسلسلات عربية",
        "$mobileUrl/category/مسلسلات-اجنبي" to "مسلسلات أجنبية",
        "$mobileUrl/category/مسلسلات-تركية" to "مسلسلات تركية",
        "$mobileUrl/category/مسلسلات-اسيوية" to "مسلسلات آسيوية",
        "$mobileUrl/category/مسلسلات-هندية" to "مسلسلات هندية",
        "$mobileUrl/category/مسلسلات-مدبلجة" to "مسلسلات مدبلجة",
        "$mobileUrl/category/مسلسلات-انمي" to "مسلسلات أنمي",
        "$mobileUrl/category/برامج-تلفزيونية" to "برامج تلفزيونية",
        "$mobileUrl/category/عروض-مصارعة" to "عروض مصارعة",
        "$mobileUrl/category/مسلسلات-رمضان-2026" to "مسلسلات رمضان 2026",
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

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".Thumb--GridItem a") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.startsWith(mainUrl) && !href.startsWith(altUrl)) return null
        if (href.contains("/category/") || href.contains("/wp-") ||
            href.endsWith("/movies/") || href.endsWith("/series/") ||
            href.endsWith("/episodes/") || href == "$mainUrl/" || href == "$altUrl/"
        ) return null
        val title = cleanTitle(a.attr("title").ifBlank { a.text() })
        if (title.isBlank()) return null
        val style = selectFirst("span[data-lazy-style]")?.attr("data-lazy-style") ?: ""
        var poster = Regex("""url\((https?://[^)]+)\)""").find(style)?.groupValues?.get(1)
            ?.let { fixUrlNull(it) }
        if (poster == null) {
            val img = selectFirst("img")
            poster = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))
                ?.takeIf { !it.contains("load") && !it.contains("blank") }
        }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val decoded = decode(href)
        val anime = isAnime(decoded)
        val seriesLike = href.contains("/series/") || decoded.contains("مسلسل") || decoded.contains("الحلقة")
        return when {
            anime && seriesLike -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            seriesLike -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ---------- wecima.mobile cards ----------

    private fun Element.toMobileCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mobileUrl)) return null
        if (!href.contains("/episode/") && !href.contains("/film/")) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("src"))?.takeIf { it.startsWith("http") }
            ?: fixUrlNull(img?.attr("data-src"))
        val title = cleanTitle(
            selectFirst("p.title")?.text() ?: img?.attr("alt") ?: attr("title")
        )
        if (title.isBlank()) return null
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val decoded = decode(href)
        val anime = isAnime(decoded)
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

    private fun Document.parseCards(): List<SearchResponse> {
        // Mobile documents carry a.show-card cards; WP documents don't.
        if (select("a.show-card").isNotEmpty()) return parseMobileCards()
        val seen = LinkedHashMap<String, SearchResponse>()
        select(".GridItem").forEach { item ->
            val card = runCatching { item.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(card.url, card)
        }
        if (seen.isEmpty()) {
            // Fallback: any post anchor carrying a title.
            select("a[href][title]").forEach { a ->
                val card = runCatching { a.toCard() }.getOrNull() ?: return@forEach
                seen.putIfAbsent(card.url, card)
            }
        }
        return seen.values.toList()
    }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))

    private fun Document.pagePlot(): String? =
        selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()?.ifBlank { null }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sections live on wecima.mobile, which paginates ?page={n}
        // (verified on the home page and category archives).
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else "$base?page=$page"
        // v17: exactly ONE fetch per section — no retry (request
        // volume discipline; the site rate-blocks, see the v17 note).
        val fetched = fetchPage(url)
        var items = fetched.document.parseCards()
        var isDebug = false
        if (items.isEmpty()) {
            // v16: surface WHAT the app received as a visible card, so
            // an "empty catalog" report carries its own diagnosis (the
            // site's markup was verified intact the same hour: 62
            // a.show-card anchors on the home page).
            val len = runCatching { fetched.document.html().length }.getOrDefault(-1)
            items = listOf(
                newMovieSearchResponse(
                    "DEBUG cat code=${fetched.code} cards=0 len=$len via=${fetched.host}",
                    url, TvType.Movie
                ) {}
            )
            isDebug = true
        }
        return newHomePageResponse(request.name, items, hasNext = !isDebug && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mobileUrl + "/search?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        val items = fetchPage(url).document.parseCards()
        if (items.isNotEmpty()) return items
        // v16: distinguish "no results" from "fetch failed" visibly.
        return listOf(
            newMovieSearchResponse("DEBUG search cards=0", url, TvType.Movie) {}
        )
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("wecima.mobile")) return loadMobile(url)
        val document = fetchPage(url).document
        val decoded = decode(url)
        val anime = isAnime(decoded)

        if (url.contains("/series/")) {
            val title = cleanTitle(
                document.selectFirst("h1")?.text()
                    ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                    ?: throw ErrorLoadingException("No title")
            )
            val episodes = mutableListOf<Episode>()
            val seen = HashSet<String>()
            document.select("a[href]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                if ((!href.startsWith(mainUrl) && !href.startsWith(altUrl)) ||
                    href == url || !seen.add(href)
                ) return@forEach
                val dec = decode(href)
                if (!dec.contains("الحلقة")) return@forEach
                val num = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(dec)?.groupValues?.get(1)?.toIntOrNull()
                episodes += newEpisode(href) {
                    this.name = num?.let { "الحلقة $it" } ?: cleanTitle(a.attr("title"))
                    this.season = seasonOf(dec)
                    this.episode = num
                }
            }
            episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes")
            return newTvSeriesLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = document.pagePoster()
                this.plot = document.pagePlot()
            }
        }

        // An episode post delegates to its series hub when the page links one,
        // so the user lands on the full episode list.
        if (decoded.contains("الحلقة")) {
            val hub = document.select("a[href*=\"/series/\"]")
                .map { fixUrl(it.attr("href")) }
                .firstOrNull { it.contains("/series/") && it != url }
            if (hub != null) return load(hub)
        }

        val title = cleanTitle(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?: throw ErrorLoadingException("No title")
        )
        return newMovieLoadResponse(
            title, url,
            if (anime) TvType.Anime else TvType.Movie,
            url
        ) {
            this.posterUrl = document.pagePoster()
            this.plot = document.pagePlot()
        }
    }

    // Dean Edwards packer blocks: eval(function(p,a,c,k,e,d){...}('payload',
    // radix, count, 'k1|k2|...'.split('|'))). Embed hosts on the tattoo side
    // (Filelions/StreamWish family) hide their player config this way;
    // unpacked, it carries a plain file:"..." URL. Added v7 as the final
    // fallback of the scan below — the generalized AnimeZid lesson: never
    // stop at an unexpanded intermediate player page.
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Field diagnostics: stage markers are surfaced as one playable DEBUG
        // source when nothing playable is found.
        val diag = mutableListOf<String>()
        if (data.contains("wecima.mobile")) {
            return loadLinksMobile(data, diag, subtitleCallback, callback)
        }
        // A WordPress-domain item (an old gripe/tattoo entry, e.g. from
        // the user's history) whose slug also exists on wecima.mobile is
        // served by the MOBILE chain — its 13 standard servers instead of
        // the WP domains' single proprietary player (whose stream Worker
        // is operator-dead). Added in v10 after Mohamed's أحلى نصيب ep1
        // screenshot: a history entry ran via=gripe into p1/p2/p3=500
        // while the same episode sits on mobile with working servers.
        val wpSlug = data.trimEnd('/').substringAfterLast("/")
        if (wpSlug.isNotBlank() && !data.contains("wecima.mobile")) {
            var twinFound: String? = null
            for (kind in listOf("episode", "film")) {
                val twin = "$mobileUrl/$kind/$wpSlug"
                val twinText = runCatching {
                    app.get(twin, headers = browserHeaders).text
                }.getOrNull()
                if (twinText != null && twinText.contains("/watch/")) {
                    twinFound = twin
                    break
                }
            }
            if (twinFound == null && decode(wpSlug).contains("الحلقة")) {
                // Ordinal spellings differ per post (الاولي vs الاولى —
                // the exact twin 404s), so discover the same series +
                // episode number through mobile search instead.
                val decodedSlug = decode(wpSlug)
                val stem = decodedSlug.substringBefore("-الحلقة").substringBefore("-الموسم")
                val epNum = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(decodedSlug)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val query = stem.removePrefix("مسلسل-").removePrefix("انمي-")
                    .replace('-', ' ').trim()
                val searchDoc = runCatching {
                    app.get(
                        "$mobileUrl/search?s=" + java.net.URLEncoder.encode(query, "UTF-8"),
                        headers = browserHeaders
                    ).document
                }.getOrNull()
                if (searchDoc != null) {
                    for (a in searchDoc.select("a.show-card")) {
                        val href = fixUrl(a.attr("href"))
                        if (!href.startsWith(mobileUrl) || !href.contains("/episode/")) continue
                        val dec = decode(href)
                        val candStem = dec.substringAfter("/episode/")
                            .substringBefore("-الحلقة").substringBefore("-الموسم")
                        val candEp = Regex("""الحلقة\s*-?\s*(\d{1,4})""").find(dec)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        if (candStem == stem && (epNum == null || candEp == epNum)) {
                            twinFound = href
                            break
                        }
                    }
                }
            }
            if (twinFound != null) {
                diag += "twin=hit"
                return loadLinksMobile(twinFound, diag, subtitleCallback, callback)
            }
        }
        val fetched = runCatching { fetchPage(data) }.getOrNull()
        diag += "page=${fetched?.code ?: "err"}"
        diag += "via=${fetched?.host?.removePrefix("https://wecima.") ?: "?"}"
        if (fetched == null) {
            emitDebug(diag, callback)
            return true
        }
        val document = fetched.document
        val servers = LinkedHashSet<String>()
        document.select("[data-watch]").forEach {
            val link = it.attr("data-watch").trim()
            if (link.startsWith("http")) servers += fixUrl(link)
        }
        document.select(".WatchArea iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http")) servers += fixUrl(src)
        }
        diag += "servers=${servers.size}"

        var found = false
        for (server in servers) {
            if (loadExtractor(server, data, subtitleCallback, callback)) {
                found = true
            }
        }

        if (!found) {
            // Scan each server page (and the download token pages) for a
            // direct playable file — the standard layout of such players.
            val scanTargets = LinkedHashSet<String>()
            scanTargets += servers
            document.select(".List--Download--Wecima--Single a[href]").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http")) scanTargets += fixUrl(href)
            }
            var scanned = false
            for (target in scanTargets) {
                var text: String? = null
                for (attempt in 1..2) {
                    text = runCatching {
                        app.get(target, headers = browserHeaders, referer = data).text
                    }.getOrNull()
                    if (text != null) break
                    Thread.sleep(1500)
                }
                val pageText = text ?: continue
                // The site's player page (ArtPlayer) carries the stream as a
                // JS constant: const videoUrl = "..." — either an HLS master
                // (with escaped slashes) or a redirector/file URL from which
                // the player itself derives v.mp4 quality variants. v1's
                // extension sweep missed both shapes (on-device scan=miss).
                val videoUrl = Regex("""videoUrl\s*=\s*"([^"]+)"""").find(pageText)
                    ?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
                    val isHls = videoUrl.contains(".m3u8")
                    // The header set a browser media fetch presents; the
                    // redirector behind videoUrl refuses plainer clients.
                    val streamHeaders = browserHeaders + mapOf(
                        "Accept" to "*/*",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "no-cors",
                        "Sec-Fetch-Site" to "cross-site"
                    )
                    if (isHls) {
                        callback(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                                this.referer = target
                                this.headers = streamHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        // Probe the stream from inside loadLinks with
                        // three header profiles; the first profile the
                        // server accepts (2xx) wins the emission, and
                        // every code is recorded for the DEBUG source.
                        val desktopHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                            "Accept" to "*/*",
                            "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
                            "Sec-Fetch-Dest" to "video",
                            "Sec-Fetch-Mode" to "no-cors",
                            "Sec-Fetch-Site" to "cross-site"
                        )
                        val profiles = listOf(
                            streamHeaders,
                            desktopHeaders,
                            mapOf(
                                "User-Agent" to browserHeaders.getValue("User-Agent"),
                                "Accept" to "*/*"
                            )
                        )
                        var winHeaders = streamHeaders
                        var winUrl = videoUrl.orEmpty()
                        for ((index, profile) in profiles.withIndex()) {
                            val probe = runCatching {
                                app.get(
                                    videoUrl,
                                    headers = profile + mapOf("Range" to "bytes=0-0"),
                                    referer = target
                                )
                            }.getOrNull()
                            diag += "p${index + 1}=${probe?.code ?: "err"}"
                            if (probe != null && probe.code in 200..299) {
                                winHeaders = profile
                                val landed = probe.url.takeIf { it.startsWith("http") }
                                if (landed != null && landed != videoUrl) {
                                    winUrl = landed
                                    diag += "final=${landed.substringBefore('?').take(60)}"
                                }
                                break
                            }
                        }
                        if (winUrl != videoUrl) {
                            callback(
                                newExtractorLink(name, "$name مباشر", winUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = target
                                    this.headers = winHeaders
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                        callback(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = target
                                this.headers = winHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        if (videoUrl.contains("v.mp4")) {
                            for ((label, quality) in listOf(
                                "1080" to Qualities.P1080, "720" to Qualities.P720,
                                "480" to Qualities.P480, "360" to Qualities.P360
                            )) {
                                callback(
                                    newExtractorLink(
                                        name, "$name ${label}p",
                                        videoUrl.replace("v.mp4", "$label.mp4"),
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = target
                                        this.headers = winHeaders
                                        this.quality = quality.value
                                    }
                                )
                            }
                        }
                    }
                    diag += "vu:hit"
                    scanned = true
                    found = true
                    break
                }
                // Second player shape: an hls.js page whose stream constant
                // is videoSrc, pointing at a playlist disguised as .txt.
                val videoSrc = Regex("""videoSrc\s*=\s*"([^"]+)"""").find(pageText)
                    ?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                if (!videoSrc.isNullOrBlank() && videoSrc.startsWith("http")) {
                    val srcIsHls = videoSrc.contains(".m3u8") || videoSrc.contains(".txt") ||
                        videoSrc.contains("hls")
                    callback(
                        newExtractorLink(
                            name, name, videoSrc,
                            if (srcIsHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = target
                            this.headers = browserHeaders + mapOf(
                                "Accept" to "*/*",
                                "Sec-Fetch-Dest" to "video",
                                "Sec-Fetch-Mode" to "no-cors",
                                "Sec-Fetch-Site" to "cross-site"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    diag += "vs:hit"
                    scanned = true
                    found = true
                    break
                }
                val direct = Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(pageText.replace("\\/", "/"))?.groupValues?.get(1)
                    ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageText)?.value
                    ?: Regex("""https?://[^\s"'<>]+/master\.txt[^\s"'<>]*""").find(pageText)?.value
                    ?: Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(pageText)?.value
                    ?: unpackPackedJs(pageText).firstNotNullOfOrNull { unpacked ->
                        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.value
                            ?: Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(unpacked)?.value
                    }
                if (direct != null) {
                    callback(
                        newExtractorLink(
                            name, name, direct,
                            if (direct.contains(".m3u8") || direct.contains("master.txt")) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                        ) {
                            this.referer = target
                            this.headers = browserHeaders + mapOf(
                                "Accept" to "*/*",
                                "Sec-Fetch-Dest" to "video",
                                "Sec-Fetch-Mode" to "no-cors",
                                "Sec-Fetch-Site" to "cross-site"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    scanned = true
                    found = true
                    break
                }
            }
            diag += "scan=${if (scanned) "hit" else "miss"}"
        }

        // The DEBUG source always rides along (v4): its markers are the
        // only field readout, and hiding them when links exist is what
        // kept v3's probe result from ever reaching the user.
        emitDebug(diag, callback)
        return true
    }

    // ---------- wecima.mobile load / playback ----------

    /** Mobile series title: the slug stem before the episode marker. */
    private fun mobileSeriesTitle(decodedSlug: String): String {
        val stem = decodedSlug.substringBefore("-الحلقة").substringBefore("-الموسم")
        return stem.removePrefix("مسلسل-").removePrefix("انمي-")
            .replace('-', ' ').trim()
    }

    private suspend fun loadMobile(url: String): LoadResponse {
        // (v17: the v15 background prefetch call was REMOVED — its
        // request volume from the user's IP is the prime suspect for
        // the site's 403 rate-block of the app; see the v17 note.)
        var document = fetchPage(url).document
        var rawTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.title()
        if (cleanTitle(rawTitle).isBlank()) {
            // A stripped/blocked fetch (e.g. the tail of the site's 403
            // cooldown — Mohamed's White Dragon tap at 13:10 errored
            // "No title" while the catalog already worked at 13:11 and
            // the page itself is complete). ONE quiet retry, then a
            // slug-derived title as the last resort — never a dead end.
            Thread.sleep(2000)
            document = fetchPage(url).document
            rawTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?: document.title()
        }
        val title = cleanTitle(rawTitle).ifBlank {
            // Only post URLs carry a naming slug — for anything else
            // (e.g. a tapped DEBUG card pointing at the bare domain)
            // stay blank and let the "No title" error stand.
            if (url.contains("/film/") || url.contains("/episode/")) {
                decode(url).substringAfter("/film/").substringAfter("/episode/")
                    .trimEnd('/').removePrefix("فيلم-").removePrefix("مسلسل-")
                    .replace('-', ' ').trim()
            } else ""
        }
        if (title.isBlank()) throw ErrorLoadingException("No title")
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.trim()?.ifBlank { null }
        val decoded = decode(url)
        val anime = isAnime(decoded)

        return if (url.contains("/episode/")) {
            // The episode page links its series' other episodes; the
            // same-series set is the one sharing this slug's stem
            // (verified live: سيدو وحورية ep3 links eps 1-4 of itself,
            // alongside other shows' latest episodes, which the stem
            // filter drops).
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
                if (href.startsWith(mobileUrl)) addEpisode(href)
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

    // Raw synchronous clients for the phase-1 pool (NiceHttp calls are
    // suspend and cannot run on pool threads). Timeouts are generous;
    // the measured per-call cost is under a second on a warm session.
    private val rawClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val rawClientNoFollow by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * One server of the mobile protocol, run on a pool thread: POST
     * /watch/resolve {key, index}, then GET the /m/ fragment WITHOUT
     * following redirects — its Location header is the real embed (the
     * ~765-byte fragment answers in ~0.3 s; following the redirect
     * would download the whole embed page just to learn its URL).
     */
    private fun resolveOneMobile(
        index: Int,
        key: String,
        csrf: String,
        cookieHeader: String,
        watchUrl: String
    ): String? {
        val ua = browserHeaders["User-Agent"] ?: "Mozilla/5.0"
        val body = JSONObject().put("key", key).put("index", index).toString()
        val postReq = okhttp3.Request.Builder()
            .url("$mobileUrl/watch/resolve")
            .header("User-Agent", ua)
            .header("X-CSRF-TOKEN", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Referer", watchUrl)
            .header("Cookie", cookieHeader)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        var rText: String? = null
        for (attempt in 1..2) {
            awaitPace()
            rText = runCatching {
                rawClient.newCall(postReq).execute().use { it.body?.string() }
            }.getOrNull()
            if (rText != null) break
            Thread.sleep(700)
        }
        val frag = (rText?.let { runCatching { JSONObject(it) }.getOrNull() })
            ?.takeIf { it.optBoolean("ok") }?.optString("url")?.takeIf { it.isNotBlank() }
            ?: return null
        val fragUrl = if (frag.startsWith("http")) frag else mobileUrl + frag
        val getReq = okhttp3.Request.Builder()
            .url(fragUrl)
            .header("User-Agent", ua)
            .header("Referer", watchUrl)
            .header("Cookie", cookieHeader)
            .build()
        awaitPace()
        return runCatching {
            rawClientNoFollow.newCall(getReq).execute().use { resp ->
                val loc = resp.header("Location")
                if (loc != null && loc.startsWith("http") && !loc.contains("wecima.mobile")) {
                    loc
                } else {
                    val t = runCatching { resp.body?.string() }.getOrNull() ?: ""
                    Regex("""url='([^']+)'""").find(t)?.groupValues?.get(1)
                        ?: Regex("""<a href="([^"]+)""").find(t)?.groupValues?.get(1)
                }
            }
        }.getOrNull()
    }

    /**
     * Mobile playback (traced end-to-end live on 2026-10-16): the post's
     * /watch/{slug} page carries an inline `const servers = [...]` list
     * (12-13 standard hosts), a per-page watchResolveKey and a CSRF
     * token. POST /watch/resolve {key, index} with the session cookies
     * answers {ok, url:"/m/{hash}"}; that fragment 302s / meta-refreshes
     * to the server's real embed (verified: m1xdrop -> m1xdrop.bz/e/...,
     * earnvids -> fastvid.cam/embed/...). EVERY server is resolved and
     * handed to the extractors — no first-hit stop (Mohamed's rule).
     */
    /**
     * Percent-encodes any non-ASCII characters of a URL string. The
     * site's anchors carry RAW Arabic slugs (verified in its HTML:
     * home/search/category/episode hrefs are 100% raw Arabic). okhttp
     * percent-encodes a URL in the request line by itself, but a
     * raw-Arabic string used as a HEADER value (referer) throws
     * IllegalArgumentException before any request is sent — the true
     * cause of the v9-v11 on-device "watch=err | werr=
     * IllegalArgumentException". Every header-bound URL goes through
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

    private companion object {
        /** Post URL -> (fetch time, resolved embed URLs). Filled by a
         * background prefetch when a title page opens, so the play press
         * skips the watch + resolve phases entirely (v15). */
        val playCache =
            java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

        /** v19 pacing gate for the operator's hosts: request STARTS
         * are spaced >= 400 ms apart provider-wide. The 403 block is
         * INTERMITTENT (catalog worked 13:11, blocked again 13:18) —
         * the app fires all 17 section fetches in one burst and the
         * site answers bursts with 403s; a browser never requests
         * like that. awaitPace() is called before fetches in
         * fetchPage and before every raw call in resolveOneMobile. */
        val paceLock = Any()
        var lastReqAt = 0L
        fun awaitPace() {
            synchronized(paceLock) {
                val wait = 400L - (System.currentTimeMillis() - lastReqAt)
                if (wait > 0) Thread.sleep(wait)
                lastReqAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * Phase 1 of the mobile protocol on a 5-thread pool (raw okhttp;
     * NiceHttp is suspend-only and cannot run on pool threads):
     * resolves servers 0..count-1 to embed URLs, in server order.
     */
    private fun phase1Mobile(
        count: Int,
        key: String,
        csrf: String,
        cookieHeader: String,
        watchUrl: String,
        diag: MutableList<String>
    ): List<Pair<Int, String>> {
        val hits = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        val fails = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val latch = java.util.concurrent.CountDownLatch(count)
        val t0 = System.currentTimeMillis()
        for (index in 0 until count) {
            pool.execute {
                try {
                    val embed = resolveOneMobile(index, key, csrf, cookieHeader, watchUrl)
                    if (embed != null) hits += index to embed else fails += index
                } catch (e: Exception) {
                    fails += index
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
        pool.shutdown()
        diag += "p1ms=${System.currentTimeMillis() - t0}"
        if (fails.isNotEmpty()) diag += "fails=${fails.sorted()}"
        return hits.sortedBy { it.first }
    }

    /** Extractor hosts that usually resolve in one fast fetch go first;
     * the rest keep the site's order (the sort is stable). */
    private fun hostPriority(url: String): Int {
        val h = url.lowercase()
        return when {
            h.contains("mixdrop") || h.contains("m1xdrop") -> 0
            h.contains("doodstream") || h.contains("d0o0d") || h.contains("dood") -> 1
            h.contains("voe.sx") || h.contains("voe") -> 2
            h.contains("streamtape") -> 3
            h.contains("uqload") -> 4
            h.contains("mp4upload") -> 5
            h.contains("streamwish") || h.contains("hgcloud") -> 6
            h.contains("filemoon") || h.contains("byse") -> 7
            else -> 10
        }
    }

    /**
     * Background prefetch (v15): when a title page opens, resolve its
     * servers on a plain thread (raw okhttp only — no suspend calls) and
     * cache the embeds, so the play press goes straight to extractors.
     * Mohamed's report: even a film took ~22 s to open, most of it the
     * watch + resolve phases running AFTER the play press.
     */
    private fun prefetchMobileAsync(url: String) {
        Thread {
            runCatching {
                val safe = pctEncodeNonAscii(url)
                val watchUrl = safe.replace("/episode/", "/watch/").replace("/film/", "/watch/")
                val ua = browserHeaders["User-Agent"] ?: "Mozilla/5.0"
                val req = okhttp3.Request.Builder().url(watchUrl)
                    .header("User-Agent", ua)
                    .header("Referer", safe)
                    .build()
                val session = rawClient.newCall(req).execute().use { resp ->
                    val cookies = resp.headers("Set-Cookie").mapNotNull { sc ->
                        val nv = sc.substringBefore(";").trim()
                        if (nv.contains("=")) nv else null
                    }
                    (resp.body?.string() ?: "") to cookies.joinToString("; ")
                }
                val text = session.first
                if (text.isBlank()) return@runCatching
                val key = Regex("""watchResolveKey\s*=\s*"([^"]+)"""").find(text)
                    ?.groupValues?.get(1) ?: return@runCatching
                val csrf = Regex("""csrfToken\s*=\s*"([^"]+)"""").find(text)
                    ?.groupValues?.get(1) ?: return@runCatching
                val serversJson = Regex("""const servers = (\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1) ?: return@runCatching
                val count = runCatching { org.json.JSONArray(serversJson).length() }
                    .getOrDefault(0)
                if (count == 0) return@runCatching
                val sink = mutableListOf<String>()
                val hits = phase1Mobile(count, key, csrf, session.second, watchUrl, sink)
                if (hits.isNotEmpty()) {
                    playCache[url] = System.currentTimeMillis() to hits.map { it.second }
                }
            }
        }.start()
    }

    private suspend fun loadLinksMobile(
        data: String,
        diag: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val safeData = pctEncodeNonAscii(data)
        if (safeData != data) diag += "enc=1"
        val watchUrl = safeData.replace("/episode/", "/watch/").replace("/film/", "/watch/")

        // v15: a background prefetch at page-open time may already hold
        // this title's embeds — then the play press skips everything
        // below and goes straight to the extractors.
        var embeds: List<String>? = null
        val cached = playCache[data]
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60 * 1000L) {
            diag += "cache=hit"
            embeds = cached.second
        }
        if (embeds == null) {
            // The host is slow and the phone-side fetch can die at
            // transport level (v9 on-device: "watch=err"). Retry with
            // backoff; the exception class goes to the DEBUG line.
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
            if (res == null) {
                emitDebug(diag, callback)
                return true
            }
            val cookies = runCatching { res.cookies }.getOrNull().orEmpty()
            val text = res.text
            val key = Regex("""watchResolveKey\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            val csrf = Regex("""csrfToken\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            val serversJson = Regex("""const servers = (\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)
            val servers = mutableListOf<String>()
            if (serversJson != null) {
                val arr = runCatching { org.json.JSONArray(serversJson) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        servers += arr.optJSONObject(i)?.optString("name") ?: "server$i"
                    }
                }
            }
            diag += "servers=${servers.size}"
            if (key == null || csrf == null || servers.isEmpty()) {
                emitDebug(diag, callback)
                return true
            }
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            var hits = phase1Mobile(servers.size, key, csrf, cookieHeader, watchUrl, diag)
            if (hits.isEmpty()) {
                // Re-warm once: fresh watch page -> fresh session.
                diag += "rewarm=1"
                val res2 = runCatching {
                    app.get(watchUrl, headers = browserHeaders, referer = safeData)
                }.getOrNull()
                val text2 = res2?.let { runCatching { it.text }.getOrNull() }
                val key2 = text2?.let {
                    Regex("""watchResolveKey\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
                }
                val csrf2 = text2?.let {
                    Regex("""csrfToken\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
                }
                val cookies2 = res2?.let { runCatching { it.cookies }.getOrNull() }.orEmpty()
                if (key2 != null && csrf2 != null) {
                    hits = phase1Mobile(
                        servers.size, key2, csrf2,
                        cookies2.entries.joinToString("; ") { e -> "${e.key}=${e.value}" },
                        watchUrl, diag
                    )
                }
            }
            val set = LinkedHashSet<String>()
            hits.forEach { set += it.second }
            diag += "resolved=${hits.size}"
            diag += "embeds=${set.size}"
            embeds = set.toList()
            if (embeds.isNotEmpty()) {
                playCache[data] = System.currentTimeMillis() to embeds
            }
        }

        // Fastest extractor hosts first; every server is still tried.
        val ordered = embeds.sortedBy { hostPriority(it) }
        var found = false
        val unresolved = mutableListOf<String>()
        for (embed in ordered) {
            if (loadExtractor(embed, watchUrl, subtitleCallback, callback)) {
                found = true
            } else {
                unresolved += embed
            }
        }
        if (!found) {
            var scanned = 0
            for (embed in unresolved) {
                if (scanEmbed(embed, watchUrl, callback)) {
                    scanned++
                    found = true
                }
            }
            diag += "scan=$scanned"
        }
        if (!found) {
            var relayed = 0
            for (embed in unresolved.take(3)) {
                relayed += resolveViaRelay(embed, watchUrl, callback)
            }
            diag += "relay=$relayed"
            if (relayed > 0) found = true
        }
        if (!found) {
            emitDebug(diag, callback)
            return true
        }
        return found
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
