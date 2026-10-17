package com.animezid

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
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
 * AnimeZid provider for CloudStream 3.
 *
 * Built against the live structure of https://animezid.org verified on 2026-10-15
 * (animezid.cam redirects there):
 * - Content cards are article.az-card > a.az-card__link with an <img> poster,
 *   a .az-card__title, and badges (.az-badge--episode holds the episode number).
 *   Series cards link to /series/{slug}/ (season cards to /series/{slug}/season/{n}/),
 *   video cards to watch.php?vid={uniq}.
 * - Category listings live at category.php?cat={slug}; videos paginate with
 *   &page={n}, series grids with &series_page={n}. Search is search.php?keywords={q}.
 * - A series page lists its seasons as cards; a season page lists its episodes
 *   as cards. A watch page's breadcrumb links back to its series.
 * - Playback is protected: watch.php links to play.php?vid={uniq}, whose page
 *   carries data-playback-create-url + data-playback-csrf (only rendered for
 *   browser-like requests). POST {create-url} with {"content_id": uniq} and the
 *   X-Playback-CSRF header creates a session listing the sources; POST
 *   {create-url}/{session}/sources/{id}/resolve returns a launch URL that
 *   redirects to (or embeds) the real host page, which the built-in extractors
 *   understand.
 *
 * v4 (2026-10-16): the relay path no longer stops at whatever the built-in
 * extractors manage to emit. On-device, only DoodStream emitted out of 29
 * relay finals while the uqload/lulustream/vidhide/streamtape embeds were
 * alive — those hosts hide the stream in packed JS or a token pattern, and
 * their stream tokens are bound to the fetching IP (verified: manifests dug
 * by the relay answer HTTP 403 from any other IP), so the dig must run on
 * the phone itself. loadViaRelay therefore digs every final the extractors
 * did not already open (see digEmbed), and the local chain digs its
 * candidates the same way.
 */
class AnimeZidProvider : MainAPI() {
    override var mainUrl = "https://animezid.org"
    override var name = "AnimeZid"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // The site only renders its protected player for browser-like requests,
    // so every call carries a desktop browser header set (verified live).
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    // Sections mirror the site's own category menu (verified live 2026-10-15).
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/category.php?cat=anime" to "أنمي",
        "$mainUrl/category.php?cat=series" to "مسلسلات",
        "$mainUrl/category.php?cat=movies" to "أفلام",
        "$mainUrl/category.php?cat=cartoon" to "كرتون",
        "$mainUrl/category.php?cat=dubbed-anime" to "أنمي مدبلج",
        "$mainUrl/category.php?cat=translated-anime" to "أنمي مترجم",
        "$mainUrl/category.php?cat=dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/category.php?cat=dubbed-animation" to "أفلام انيميشن مدبلجة",
        "$mainUrl/category.php?cat=subbed-animation" to "أفلام انيميشن مترجمة",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون",
        "$mainUrl/category.php?cat=new-eps" to "أحدث الحلقات",
        "$mainUrl/category.php?cat=new-movies" to "أحدث الأفلام",
    )

    // ---------- helpers ----------

    /** Parse one az-card anchor into a search response. */
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (!href.contains("/series/") && !href.contains("watch.php")) return null

        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("src"))?.takeUnless { it.contains("no-thumbnail") }
        val title = (
            selectFirst(".az-card__title")?.text()
                ?: img?.attr("alt")
                ?: attr("aria-label")
            ).trim()
        if (title.isBlank()) return null
        val year = Regex("""(19|20)\d{2}""").find(selectFirst(".az-card__meta")?.text().orEmpty())
            ?.value?.toIntOrNull()
        val hasEpisodeBadge = selectFirst(".az-badge--episode") != null

        return when {
            href.contains("/series/") || hasEpisodeBadge ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            else ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
        }
    }

    /** All unique content cards on a listing page (home / category / search). */
    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("article.az-card a.az-card__link").forEach { a ->
            val item = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(item.url, item)
        }
        return seen.values.toList()
    }

    private fun Document.seriesTitle(): String? {
        selectFirst("h1")?.text()?.trim()?.ifBlank { null }?.let { return it }
        // Season pages carry the series name in the hero stats ("المسلسل" label).
        select("small").firstOrNull { it.text().trim() == "المسلسل" }
            ?.parent()?.selectFirst("bdi")?.text()?.trim()?.ifBlank { null }?.let { return it }
        return selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?.substringBefore("|")?.trim()?.ifBlank { null }
    }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            ?: fixUrlNull(selectFirst(".az-cinema-poster img")?.attr("src"))
            ?: fixUrlNull(selectFirst("img[src*=\"/uploads/\"]")?.attr("src"))

    private fun Document.pagePlot(): String? =
        selectFirst(".az-series-description__copy")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst(".az-cinema-summary")?.text()?.trim()?.ifBlank { null }
            ?: select("main p").map { it.text().trim() }
                .filter { it.length in 60..2500 }
                .maxByOrNull { it.length }

    private fun Document.pageYear(): Int? =
        Regex("""(19|20)\d{2}""").find(selectFirst(".az-cinema-meta")?.text().orEmpty())
            ?.value?.toIntOrNull()

    private fun seasonNumberOf(href: String): Int? =
        Regex("""/season/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

    /** Episode cards (watch.php anchors) on a season / series page. */
    private fun Document.parseEpisodes(seasonNumber: Int?): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()
        select("article.az-card a.az-card__link[href*=\"watch.php\"], .az-cinema-episode-grid a[href*=\"watch.php\"]")
            .forEach { a ->
                val href = fixUrl(a.attr("href"))
                if (!href.contains("watch.php")) return@forEach
                val titleText = a.selectFirst(".az-card__title")?.text()?.trim()
                    ?: a.text().trim()
                val epNum = a.selectFirst(".az-badge--episode strong")?.text()?.toIntOrNull()
                    ?: Regex("""الحلقة\s*(\d+)""").find(titleText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""الحلقة\s*(\d+)""").find(a.attr("aria-label"))?.groupValues?.get(1)?.toIntOrNull()
                val ep = newEpisode(href, {
                    this.name = titleText.ifBlank { epNum?.let { n -> "الحلقة $n" } ?: "حلقة" }
                    this.season = seasonNumber
                    this.episode = epNum
                })
                byUrl.putIfAbsent(href, ep)
            }
        return byUrl.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    // ---------- MainAPI ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Category pages paginate videos with &page={n} and series grids with
        // &series_page={n}; advancing both keeps mixed grids moving together.
        val url = if (page <= 1) request.data else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page&series_page=$page"
        }
        val document = app.get(url, headers = browserHeaders).document
        val items = document.parseCards()
        return newHomePageResponse(
            request.name, items,
            hasNext = request.data.contains("category.php") && items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return app.get(url, headers = browserHeaders).document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document

        return when {
            url.contains("/season/") -> {
                val title = document.seriesTitle() ?: throw ErrorLoadingException("No title")
                newTvSeriesLoadResponse(
                    title, url, TvType.Anime,
                    document.parseEpisodes(seasonNumberOf(url))
                ) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                }
            }

            url.contains("/series/") -> {
                val title = document.seriesTitle() ?: throw ErrorLoadingException("No title")
                val seasonLinks = LinkedHashMap<String, Int?>()
                document.select("a[href*=\"/season/\"]").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (href.contains("/season/")) seasonLinks.putIfAbsent(href, seasonNumberOf(href))
                }
                val episodes = mutableListOf<Episode>()
                if (seasonLinks.isEmpty()) {
                    episodes += document.parseEpisodes(1)
                } else {
                    seasonLinks.entries.forEachIndexed { index, (seasonUrl, seasonNum) ->
                        val seasonDoc = runCatching {
                            app.get(seasonUrl, headers = browserHeaders).document
                        }.getOrNull() ?: return@forEachIndexed
                        episodes += seasonDoc.parseEpisodes(seasonNum ?: (index + 1))
                    }
                }
                newTvSeriesLoadResponse(
                    title, url, TvType.Anime,
                    episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
                ) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                }
            }

            url.contains("watch.php") -> {
                // An episode's watch page links back to its series in the
                // breadcrumb; jump there so the user gets the episode list.
                val seriesUrl = document.select(".az-cinema-breadcrumb a[href*=\"/series/\"]")
                    .map { fixUrl(it.attr("href")) }
                    .firstOrNull { it.contains("/series/") && !it.contains("/season/") }
                if (seriesUrl != null && seriesUrl != url) {
                    load(seriesUrl)
                } else {
                    val title = document.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                        ?: throw ErrorLoadingException("No title")
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = document.pagePoster()
                        this.plot = document.pagePlot()
                        this.year = document.pageYear()
                    }
                }
            }

            else -> throw ErrorLoadingException("Unsupported url")
        }
    }

    /**
     * Asks the relay server (the Stremio addon service) to run the whole
     * protected playback-session flow for [vid] server-side and feeds the
     * returned final host URLs to the built-in extractors, then (v4) digs
     * phone-side whatever the extractors could not open. Returns true when
     * at least one playable link was emitted.
     */
    private suspend fun loadViaRelay(
        vid: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val relayUrl = "https://egydead-stremio.onrender.com/relay/animezid?vid=$vid"
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(relayUrl).text }.getOrNull()
            if (text != null) break
            Thread.sleep(2000)
        }
        val body = text ?: run {
            diag += "relay:fetch-fail"
            return false
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: run {
            diag += "relay:bad-json"
            return false
        }
        if (!json.optBoolean("ok")) {
            diag += "relay:not-ok"
            return false
        }
        val finals = json.optJSONArray("finals")
        diag += "relay:finals=${finals?.length() ?: 0}"
        var found = false
        var dug = 0
        val extracted = HashSet<String>()
        val seenDig = HashSet<String>()
        if (finals != null) {
            // v8 SPEED (v7 on-device: still slow). Diagnosis from the
            // numbers: the relay answers in ~4.5-7 s with all 29 finals;
            // the swamp was this pass walking them SEQUENTIALLY - every
            // dead host cost its own slow failed fetch, one after another.
            // v8 runs the extractor attempts in PARALLEL WINDOWS
            // (CloudStream amap, 6 at a time): the wait collapses from
            // the SUM of the failures to roughly the slowest single host
            // in the window. Ordering discipline from v7 is kept (dead
            // classes demoted to the tail, MegaMax player page skipped -
            // the relay already expanded it), the dig stage still runs
            // phone-side for the proven diggable hosts (their tokens are
            // IP-bound to the fetching device), and the whole pass keeps
            // a hard budget: stop at 6 emitted links, at 10 s once
            // anything playable exists, 18 s absolute.
            val passStart = System.currentTimeMillis()
            val emitted = java.util.concurrent.atomic.AtomicInteger(0)
            val parFound = java.util.concurrent.atomic.AtomicBoolean(false)
            val emitLock = Any()
            val counting: (ExtractorLink) -> Unit = { link ->
                emitted.incrementAndGet()
                synchronized(emitLock) { callback(link) }
            }
            val rawFinals = (0 until finals.length())
                .map { finals.optJSONObject(it)?.optString("url").orEmpty() }
                .filter { it.isNotBlank() && !it.contains("megamax.me") }
            val demotedHosts = listOf("mp4upload.", "ds2play.", "rpmhub.", "byseraguci.", "streamtape.")
            val orderedFinals = rawFinals.filter { u -> demotedHosts.none { u.contains(it) } } +
                rawFinals.filter { u -> demotedHosts.any { u.contains(it) } }
            val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
            val parExtracted = java.util.Collections.synchronizedSet(HashSet<String>())
            var idx = 0
            while (idx < orderedFinals.size) {
                val elapsed = System.currentTimeMillis() - passStart
                if (emitted.get() >= 6) break
                if (emitted.get() > 0 && elapsed > 10_000) { diag += "pass:deadline"; break }
                if (emitted.get() == 0 && elapsed > 18_000) { diag += "pass:deadline0"; break }
                val window = orderedFinals.subList(idx, minOf(idx + 6, orderedFinals.size))
                window.amap { url ->
                    if (loadExtractor(url, referer, subtitleCallback, counting)) {
                        parFound.set(true)
                        parExtracted += url
                    } else {
                        failed += url
                    }
                }
                idx += window.size
            }
            if (parFound.get()) found = true
            extracted += parExtracted
            // Dig stage: sequential by design (each dig is a full page
            // fetch + unpack), only for finals the parallel pass could
            // not open, only while short on links and inside budget.
            for (url in failed) {
                if (dug >= 3 || emitted.get() >= 5) break
                if (System.currentTimeMillis() - passStart > 12_000) break
                val diggable = url.contains("uqload.") || url.contains("lulustream") ||
                    url.contains("streamtape") || url.contains("vidhide")
                if (!diggable) continue
                val key = if (url.contains("uqload.", ignoreCase = true)) {
                    "uqload/" + url.substringAfter("://").substringAfter("/", "")
                } else url
                if (!seenDig.add(key)) continue
                if (key.startsWith("uqload/") &&
                    extracted.any { it.contains("uqload.", ignoreCase = true) }
                ) continue
                if (digEmbed(url, referer, counting, diag)) dug++
            }
            diag += "pass:par"
            diag += "pass=" + ((System.currentTimeMillis() - passStart) / 1000) + "s"
        }
        diag += "relay:digs=$dug"
        if (found || dug > 0) diag += "relay:hit"
        return found || dug > 0
    }

    // ------------------------------------------------------------ v4 dig
    // Hosts whose stream is never statically diggable (captcha gates,
    // pass_md5 or form two-steps, JS app shells) — the built-in extractors
    // own them where they work; digging them would only burn time.
    private val digSkipHost = Regex(
        """(megamax\.|mixdrop|mxdrop|krakenfiles|upns\.|dood|playmogo|ds2play|streamwish|rpmhub|rpmshare|byse|mp4upload|savefiles)""",
        RegexOption.IGNORE_CASE
    )
    private val directJunk = Regex(
        """(\.vtt|\.svg|\.png|\.jpe?g|\.gif|logo|thumb|/images/|sprite)""",
        RegexOption.IGNORE_CASE
    )

    private fun hostOf(url: String): String =
        Regex("""://([^/]+)""").find(url)?.groupValues?.get(1).orEmpty()

    /** First non-junk direct media URL in a page text (master playlist wins). */
    private fun extractDirectAz(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            if (!directJunk.containsMatchIn(it.groupValues[1])) return it.groupValues[1]
        }
        val m3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean)
            .map { it.value }.filter { !directJunk.containsMatchIn(it) }.toList()
        if (m3u8s.isNotEmpty()) {
            return m3u8s.firstOrNull { it.contains("master") } ?: m3u8s.first()
        }
        return Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean)
            .map { it.value }.firstOrNull { !directJunk.containsMatchIn(it) }
    }

    // Dean Edwards packer blocks — same unpacker as the Brestj/MyCima
    // providers (proven on hdup400/minochinos packed players).
    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

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

    /**
     * Phone-side dig of one embed page: fetch it with the provider's own
     * browser headers and pull the direct stream out — a streamtape
     * get_video token URL, or a direct file in the raw or unpacked page.
     * The emitted link's referer is the embed page itself. Returns true
     * when a link was emitted.
     */
    private suspend fun digEmbed(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val host = hostOf(embed)
        if (host.isBlank() || digSkipHost.containsMatchIn(host)) return false
        val label = host.removePrefix("www.").substringBefore(".")
            .replaceFirstChar { it.uppercase() }
        val text = runCatching {
            app.get(embed, headers = browserHeaders, referer = referer).text
        }.getOrNull() ?: return false
        val direct: String?
        val directType: ExtractorLinkType
        if (host.contains("streamtape", ignoreCase = true)) {
            val m = Regex("""get_video\?id=[^"'&\s]+&expires=\d+&ip=[^"'&\s]+&token=[^"'&\s]+""")
                .find(text)
            direct = m?.let { "https://$host/${it.value}" }
            directType = ExtractorLinkType.VIDEO
        } else {
            direct = extractDirectAz(text)
                ?: unpackPackedJs(text).firstNotNullOfOrNull { extractDirectAz(it) }
            directType = if (direct?.contains(".m3u8") == true) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
        }
        if (direct.isNullOrBlank()) {
            diag += "dig:$label=0"
            return false
        }
        callback(
            newExtractorLink(label, label, direct, directType) {
                this.referer = embed
                this.quality = Qualities.Unknown.value
            }
        )
        diag += "dig:$label=1"
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Field diagnostics: stage markers are surfaced as one playable DEBUG
        // source when nothing playable is found, so the failing stage can be
        // read off the player's source list.
        val diag = mutableListOf<String>()

        val vid = Regex("""vid=([A-Za-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            diag += "vid:missing"
            emitDebug(diag, callback)
            return true
        }
        // v3: server-side relay first — the relay runs the protected
        // playback-session flow from its own network (which the site
        // answers normally) and returns final host URLs ready for the
        // built-in extractors. The relay (free tier) can be asleep, so one
        // retry is allowed inside loadViaRelay; the local chain below
        // remains the fallback.
        if (loadViaRelay(vid, data, subtitleCallback, callback, diag)) return true

        val playUrl = "$mainUrl/play.php?vid=$vid"

        val playResponse = runCatching {
            app.get(playUrl, headers = browserHeaders, referer = data)
        }.getOrNull()
        if (playResponse == null) {
            diag += "play:fetch-fail"
            emitDebug(diag, callback)
            return true
        }
        val playDoc = playResponse.document
        val mainEl = playDoc.selectFirst("[data-playback-create-url]")
            ?: playDoc.selectFirst("main")
        val createUrl = mainEl?.attr("data-playback-create-url").orEmpty()
        val csrf = mainEl?.attr("data-playback-csrf").orEmpty()
        val uniq = mainEl?.attr("data-video-uniq")?.ifBlank { null } ?: vid
        diag += "protected=${mainEl?.attr("data-playback-protected") ?: "?"}"
        if (createUrl.isBlank() || csrf.isBlank()) {
            diag += "play:no-session-attrs"
            emitDebug(diag, callback)
            return true
        }
        val cookies = runCatching { playResponse.cookies }.getOrNull().orEmpty()

        val apiHeaders = browserHeaders + mapOf(
            "X-Playback-CSRF" to csrf,
            "Referer" to playUrl,
            "Origin" to mainUrl
        )
        val jsonType = "application/json".toMediaType()

        val sessionText = runCatching {
            app.post(
                createUrl,
                headers = apiHeaders,
                cookies = cookies,
                requestBody = JSONObject().put("content_id", uniq).toString()
                    .toRequestBody(jsonType)
            ).text
        }.getOrNull()
        val session = sessionText?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sessionId = session?.optString("session_id").orEmpty()
        if (sessionId.isBlank()) {
            diag += "session:fail"
            emitDebug(diag, callback)
            return true
        }
        val sourcesJson = session?.optJSONArray("sources")
        val sourceIds = mutableListOf<Pair<String, String>>()
        if (sourcesJson != null) {
            for (i in 0 until sourcesJson.length()) {
                val s = sourcesJson.optJSONObject(i) ?: continue
                if (s.optString("type") == "embedded_web") {
                    sourceIds += s.optString("id") to s.optString("provider")
                }
            }
        }
        diag += "sources=${sourceIds.size}"
        if (sourceIds.isEmpty()) {
            emitDebug(diag, callback)
            return true
        }

        // Try the sources one at a time, best-supported hosts first, and
        // STOP at the first playable result: the site throttles resolve
        // calls hard (HTTP 429 after a small burst — verified live:
        // back-to-back resolves gave 201 then nine 429s), so resolving
        // everything, or continuing after a success, kills the rest.
        val priority = listOf(
            "PlayMate", "DoodStream", "Uqload", "Abyss", "VidTube",
            "StreamP2P", "UPNShare", "RPMShare", "StreamRuby", "MegaMax"
        )
        val ordered = sourceIds.sortedBy { pair ->
            val idx = priority.indexOf(pair.second)
            if (idx < 0) priority.size else idx
        }

        var found = false
        for ((sourceId, provider) in ordered) {
            if (found) break

            // Resolve, with one backoff retry on the site's rate limit.
            var launchUrl = ""
            var resolveCode = -1
            for (attempt in 1..2) {
                val res = runCatching {
                    app.post(
                        "$createUrl/$sessionId/sources/$sourceId/resolve",
                        headers = apiHeaders,
                        cookies = cookies,
                        requestBody = "{}".toRequestBody(jsonType)
                    )
                }.getOrNull()
                resolveCode = res?.code ?: -1
                launchUrl = res?.text
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optString("launch_url").orEmpty()
                if (launchUrl.isNotBlank()) break
                if (resolveCode == 429 && attempt == 1) {
                    Thread.sleep(3000)
                } else {
                    break
                }
            }
            if (launchUrl.isBlank()) {
                diag += "resolve:$resolveCode($provider)"
                continue
            }

            // The launch URL is bound to the playback session cookie:
            // following it without the cookie returns HTTP 410 and stays on
            // animezid.org (verified live), so the cookies MUST go along.
            // The launch either redirects straight to the host's embed page
            // (the final URL after redirects) or serves a page holding the
            // host embed in an iframe. Feed both forms to the extractors.
            val launchResponse = runCatching {
                app.get(launchUrl, headers = browserHeaders, cookies = cookies, referer = playUrl)
            }.getOrNull()
            val candidates = LinkedHashSet<String>()
            launchResponse?.url?.takeIf { it.isNotBlank() && !it.contains("animezid.org") }
                ?.let { finalUrl ->
                    candidates += finalUrl
                    // The Uqload extractor does not know the .vc TLD; the
                    // same embed path works on its canonical .com domain.
                    if (finalUrl.contains("uqload.", ignoreCase = true)) {
                        candidates += finalUrl.replace(
                            Regex("""uqload\.[a-z]+""", RegexOption.IGNORE_CASE),
                            "uqload.com"
                        )
                    }
                }
            launchResponse?.document?.select("iframe[src]")?.forEach { frame ->
                val src = fixUrl(frame.attr("src"))
                if (src.startsWith("http")) candidates += src
            }
            diag += "launch($provider)=${candidates.size}"
            if (candidates.isEmpty() && launchResponse != null) {
                diag += "launch:http-${launchResponse.code}($provider)"
            }

            for (candidate in candidates) {
                if (loadExtractor(candidate, playUrl, subtitleCallback, callback)) {
                    found = true
                    break
                }
                // The MegaMax host has no built-in extractor; its player page
                // is an Inertia app whose deferred "streams" prop lists the
                // real mirrors on well-known hosts (same trick as EgyDead).
                if (candidate.contains("megamax", ignoreCase = true) &&
                    loadFromMegaMax(candidate, playUrl, subtitleCallback, callback, diag)
                ) {
                    found = true
                    break
                }
                // v4: phone-side dig for packed/token hosts the extractors
                // miss (the local chain keeps its stop-at-first-playable
                // discipline — the site throttles resolves, not host GETs,
                // but one playable source is this fallback's contract).
                if (digEmbed(candidate, playUrl, callback, diag)) {
                    found = true
                    break
                }
            }
            if (!found) Thread.sleep(600)
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

    /**
     * Resolves a MegaMax embed page (Inertia/Vue app): the initial HTML only
     * carries video metadata plus an asset "version"; the actual sources live
     * in a deferred "streams" prop fetched with an Inertia partial reload.
     * That prop lists, per quality, mirror links on well-known hosts — all
     * understood by CloudStream's built-in extractors. Same host system as
     * EgyDead's default server; verified live on 2026-10-15.
     */
    private suspend fun loadFromMegaMax(
        embed: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Boolean {
        val pageText = runCatching {
            app.get(embed, headers = browserHeaders, referer = referer).text
        }.getOrNull() ?: return false
        val pageJsonText = Regex(
            """<script[^>]*data-page[^>]*>(\{.*?\})</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(pageText)?.groupValues?.get(1) ?: return false
        val pageJson = runCatching { JSONObject(pageJsonText) }.getOrNull() ?: return false
        val version = pageJson.optString("version")
        if (version.isBlank()) return false
        val component = pageJson.optString("component").ifBlank { "files/mirror/video" }

        val streamsText = runCatching {
            app.get(
                embed,
                referer = embed,
                headers = browserHeaders + mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to component,
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        }.getOrNull() ?: return false

        val streams = runCatching {
            JSONObject(streamsText).getJSONObject("props").getJSONObject("streams")
        }.getOrNull() ?: return false
        if (streams.optString("status") != "success") return false
        val qualities = streams.optJSONArray("data") ?: return false

        var found = false
        for (i in 0 until qualities.length()) {
            val mirrors = qualities.optJSONObject(i)?.optJSONArray("mirrors") ?: continue
            for (j in 0 until mirrors.length()) {
                var link = mirrors.optJSONObject(j)?.optString("link").orEmpty()
                if (link.startsWith("//")) link = "https:$link"
                if (link.isBlank()) continue
                if (loadExtractor(link, embed, subtitleCallback, callback)) {
                    found = true
                } else if (digEmbed(link, embed, callback, diag)) {
                    found = true
                }
            }
        }
        diag += "megamax:mirrors-done"
        return found
    }
}
