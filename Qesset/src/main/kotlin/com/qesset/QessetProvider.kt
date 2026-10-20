package com.qesset

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Qesset — قصة عشق (qesset.net) provider for CloudStream 3.
 *
 * WordPress site (theme vo_2025) for Turkish series/movies subtitled
 * in Arabic, verified live on 2026-10-19. Structure:
 *
 *   series   /yeni-show/{slug}/ — og meta + ALL episode links
 *   episode  /clarus/{stem}-episode-{n}/ — links back to its
 *            /yeni-show/ page and lists every sibling episode
 *   movie    /movies/{slug}/
 *   lists    / (home rails), /son-bolumler/ (latest episodes, one
 *            page), /category/alarshif/ (series archive) and
 *            /category/yeni-filmler/ (movies), both paginated
 *            /page/{n}/; cards are <article class="post"> with the
 *            poster as a background-image and the title in the
 *            anchor's title attribute. Type is read from the URL path.
 *   search   GET /?s={query} (WordPress), same card markup.
 *   mirrors  qeseh.net / esheeq.net serve the same content; authored
 *            hrefs mix hosts, so data URLs are normalized to mainUrl.
 *
 * Playback model (the operator's own design, decoded live): every
 * post page carries ONE watch anchor to qesen.net/watch?post=<b64>
 * whose base64 payload is JSON {codeDaily, servers:[{name, id}],
 * postID, type, backUrl}. The name+id pair maps to an embed URL by a
 * fixed template table (from the player page's own script):
 *   Arab HD → arabhd.onl/embed-{id}.html      (packed → artrk HLS)
 *   estream → arabveturk.com/embed-{id}.html   (packed → artrk HLS)
 *   Red HD  → iplayerhls.com/e/{id}            (packed → premilkyway HLS)
 *   now     → extreamnow.org/embed-{id}.html
 *   box     → youdboox.com/embed-{id}.html
 *   Pro HD  → w.larhu.website/play.php?id={id}
 *   pro     → mdna.upns.online/#{id}
 *   facebook→ app.videas.fr/embed/media/{id}
 *   ok      → ok.ru/videoembed/{id}            (app extractor)
 *   dailymotion → dailymotion.com/embed/video/{id} (app extractor)
 *   youtube_in / youtube → youtube embeds      (app extractor)
 *   express → the id IS an external URL (dig + extractor attempt)
 * The custom HLS hosts are dug ON THE PHONE (embed fetch → Dean
 * Edwards unpack → signed master.m3u8); the arabhd + estream chains
 * were verified end-to-end (master → variants → decodable segments).
 * The qesen watch page itself is referer-gated (no referer → a
 * maintenance page) but the provider never needs it: the payload is
 * decoded from the post page directly. A DEBUG pseudo-link with
 * stage counters is emitted when nothing resolves.
 *
 * DUBBED lane (v2, k.3chk.net — the operator's sister site, theme
 * 3sknewv3; qesset.net itself carries NO dubbed catalog, measured):
 * the dubbed archive is /genre/serie-mudblij-q104/ (17 pages, cards
 * are <div class="EpisodeBlock"> → /video/tvshows/{slug}/, poster in
 * img[data-img], title in .EpisodeBlockTitle). Series pages carry og
 * meta and episode links /video/ep/{stem}-season-{s}-episode-{e}/.
 * The episode page's shortlink gives the post id (?p={pid}); the
 * player slots are GET /embed/{n}/{pid}/2/ WITH the episode page as
 * referer (referer-gated: a bare hit returns a gate page) and each
 * filled slot's iframe is the real host embed — census across 4
 * series: exactly 2 servers everywhere, nume=1 → miravd.com,
 * nume=2 → mwdy.cc. Both hosts are Cloudflare-challenged for every
 * datacenter client and answer "Embeds disabled" when unframed, so
 * the embeds are dug ON THE PHONE with the /embed/ page as referer
 * (the exact frame chain a browser runs); a DEBUG line reports the
 * per-host outcome (cf / disabled / ok / nodirect) when nothing
 * resolves. The camalk.net "prepare/watch" pages in the visitor
 * flow are a monetization wrapper around the same /embed/ iframes.
 */
class QessetProvider : MainAPI() {
    override var mainUrl = "https://qesset.net"
    override var name = "Qesset"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val relayBase = "https://egydead-stremio.onrender.com"
    private val dubBase = "https://k.3chk.net"

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/son-bolumler/" to "آخر الحلقات",
        "/category/alarshif/" to "مسلسلات",
        "/category/yeni-filmler/" to "أفلام",
        "/dubbed/" to "مسلسلات مدبلجة"
    )

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    /** Mirror hosts authored into hrefs → normalize data URLs to mainUrl. */
    private fun normalize(url: String): String =
        url.replace(Regex("""^(https?)://(www\.)?(qeseh|esheeq|qesen)\.net"""), mainUrl)

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s*-\s*قصة عشق.*$"""), "").trim()

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (article in doc.select("article.post")) {
            val a = article.selectFirst("a[href]") ?: continue
            val href = normalize(a.attr("abs:href").ifBlank { a.attr("href") })
            val kind = when {
                href.contains("/clarus/") -> "episode"
                href.contains("/movies/") -> "movie"
                href.contains("/yeni-show/") -> "series"
                else -> continue
            }
            var title = cleanTitle(a.attr("title"))
            if (title.isBlank()) {
                title = cleanTitle(
                    article.selectFirst("h3, h2, .title, .post-title")?.text().orEmpty()
                )
            }
            if (title.isBlank()) continue
            val style = article.selectFirst(".imgBg")?.attr("style").orEmpty()
            val poster = Regex("""url\((?:'|")?([^'")]+)(?:'|")?\)""").find(style)
                ?.groupValues?.get(1)
                ?: article.selectFirst("img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("src") }
                }?.ifBlank { null }
            out += when (kind) {
                "movie" -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
                else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
        return out
    }

    /** Dubbed cards on k.3chk.net: EpisodeBlock → /video/tvshows|movies/. */
    private fun parseDubCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (block in doc.select("div.EpisodeBlock")) {
            val a = block.selectFirst("a[href]") ?: continue
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val isMovie = href.contains("/video/movies/")
            if (!isMovie && !href.contains("/video/tvshows/")) continue
            val title = block.selectFirst(".EpisodeBlockTitle")?.text()?.trim()
                .orEmpty()
            if (title.isBlank()) continue
            val img = block.selectFirst("img")
            val poster = img?.attr("data-img")?.ifBlank { null }
                ?: img?.attr("abs:src")?.ifBlank { null }
            out += if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "/dubbed/") {
            val base = "$dubBase/genre/serie-mudblij-q104/"
            val url = if (page <= 1) base else base + "page/$page/"
            val doc = app.get(url, headers = browserHeaders).document
            val hasNext = doc.select("a[href]").any {
                it.attr("href").contains("/page/${page + 1}/")
            }
            return newHomePageResponse(
                listOf(HomePageList(request.name, parseDubCards(doc))),
                hasNext
            )
        }
        val paginated = request.data.startsWith("/category/")
        if (!paginated && page > 1) {
            return newHomePageResponse(listOf(HomePageList(request.name, emptyList())), false)
        }
        val url = if (page <= 1) mainUrl + request.data
            else mainUrl + request.data + "page/$page/"
        val doc = app.get(url, headers = browserHeaders).document
        val hasNext = paginated && doc.select("a[href]").any {
            it.attr("href").contains("/page/${page + 1}/")
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, parseCards(doc))),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, headers = browserHeaders).document
        return parseCards(doc)
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    private fun og(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=og:$prop]")?.attr("content")?.trim()?.ifBlank { null }

    private fun episodeNumberOf(url: String): Int? =
        Regex("""-episode-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * All episode-post anchors on a page, filtered to the DOMINANT
     * series stem: side rails link other shows' episodes (one link per
     * foreign stem), while the page's own series contributes the
     * largest same-stem group. Sorted by episode number.
     */
    private fun episodeLinks(doc: Document): List<Pair<String, String>> {
        val seen = LinkedHashMap<String, String>()
        for (a in doc.select("a[href]")) {
            val href = normalize(a.attr("abs:href").ifBlank { a.attr("href") })
            if (!href.contains("/clarus/")) continue
            if (seen.containsKey(href)) continue
            val label = a.attr("title").ifBlank { a.text() }.trim()
            seen[href] = label
        }
        fun stemOf(u: String): String =
            u.trimEnd('/').substringAfterLast('/')
                .replace(Regex("""-episode-\d+$"""), "")
        val dominant = seen.keys.groupBy { stemOf(it) }
            .maxByOrNull { it.value.size }?.key ?: return emptyList()
        return seen.entries
            .filter { stemOf(it.key) == dominant }
            .map { it.key to it.value }
            .sortedBy { episodeNumberOf(it.first) ?: Int.MAX_VALUE }
    }

    private fun buildEpisodes(
        links: List<Pair<String, String>>,
        poster: String?
    ): List<Episode> = links.mapIndexed { i, (href, label) ->
        val n = episodeNumberOf(href) ?: (i + 1)
        newEpisode(href) {
            this.name = label.ifBlank { "الحلقة $n" }
            this.season = 1
            this.episode = n
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("3chk.net")) return loadDub(url)
        if (url.contains("/yeni-show/")) return loadSeries(url, null)
        val doc = app.get(url, headers = browserHeaders).document
        if (url.contains("/clarus/")) {
            // An episode post: reroute to its series page when linked.
            val showLink = doc.select("a[href]").firstOrNull {
                normalize(it.attr("abs:href")).contains("/yeni-show/")
            }?.let { normalize(it.attr("abs:href").ifBlank { it.attr("href") }) }
            if (showLink != null) return loadSeries(showLink, null)
            // Fallback: build the series from this page's sibling links.
            val rawTitle = cleanTitle(og(doc, "title") ?: doc.title())
            val seriesName = rawTitle
                .replace(Regex("""\s*الحلقة\s*\d+.*$"""), "")
                .replace(Regex("""^مسلسل\s+"""), "")
                .trim()
            val poster = og(doc, "image")
            val episodes = buildEpisodes(episodeLinks(doc), poster)
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = og(doc, "description")
            }
        }
        // A movie post.
        val title = cleanTitle(og(doc, "title") ?: doc.title())
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = og(doc, "image")
            this.plot = og(doc, "description")
        }
    }

    private suspend fun loadSeries(showUrl: String, unused: Unit?): LoadResponse {
        val doc = app.get(showUrl, headers = browserHeaders).document
        val title = cleanTitle(og(doc, "title") ?: doc.title())
        val poster = og(doc, "image")
        val episodes = buildEpisodes(episodeLinks(doc), poster)
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")
        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = og(doc, "description")
        }
    }

    // ------------------------------------------------------------------
    // Dubbed lane load (k.3chk.net)
    // ------------------------------------------------------------------

    private val dubEpRegex = Regex("""-season-(\d+)-episode-(\d+)""")

    private fun dubStemOf(u: String): String =
        u.trimEnd('/').substringAfterLast('/')
            .replace(Regex("""-season-\d+-episode-\d+.*$"""), "")

    /** Own-series episode links on a 3chk page (dominant stem group). */
    private fun dubEpisodeLinks(doc: Document): List<String> {
        val seen = LinkedHashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.contains("/video/ep/") && dubEpRegex.containsMatchIn(href)) {
                seen += href
            }
        }
        val dominant = seen.groupBy { dubStemOf(it) }
            .maxByOrNull { it.value.size }?.key ?: return emptyList()
        return seen.filter { dubStemOf(it) == dominant }.sortedBy { u ->
            val m = dubEpRegex.find(u)
            val s = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = m?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE
            s * 10000 + e
        }
    }

    private fun buildDubEpisodes(links: List<String>, poster: String?): List<Episode> =
        links.mapIndexed { i, href ->
            val m = dubEpRegex.find(href)
            val s = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = m?.groupValues?.get(2)?.toIntOrNull() ?: (i + 1)
            newEpisode(href) {
                this.name = "الحلقة $e"
                this.season = s
                this.episode = e
                this.posterUrl = poster
            }
        }

    private fun cleanDubTitle(raw: String): String =
        raw.replace(Regex("""\s*موقع قصة عشق.*$"""), "")
            .replace(Regex("""\s*-\s*قصة عشق.*$"""), "").trim()

    private suspend fun loadDub(url: String): LoadResponse {
        if (url.contains("/video/movies/")) {
            val doc = app.get(url, headers = browserHeaders).document
            val title = cleanDubTitle(og(doc, "title") ?: doc.title())
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = og(doc, "image")
                this.plot = og(doc, "description")
            }
        }
        if (url.contains("/video/ep/")) {
            // Episode page: reroute to its series when the series link
            // is identifiable by the show name, else use sibling links.
            val doc = app.get(url, headers = browserHeaders).document
            val rawTitle = cleanDubTitle(og(doc, "title") ?: doc.title())
            val seriesName = rawTitle
                .replace(Regex("""\s*الحلقة\s*\d+.*$"""), "").trim()
            val showLink = doc.select("a[href]").firstOrNull { a ->
                val h = a.attr("abs:href").ifBlank { a.attr("href") }
                h.contains("/video/tvshows/") &&
                    a.attr("title").contains(seriesName.removePrefix("مسلسل").trim())
            }?.let { it.attr("abs:href").ifBlank { it.attr("href") } }
            if (showLink != null) return loadDubSeries(showLink)
            val poster = og(doc, "image")
            val episodes = buildDubEpisodes(dubEpisodeLinks(doc), poster)
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = og(doc, "description")
            }
        }
        return loadDubSeries(url)
    }

    private suspend fun loadDubSeries(showUrl: String): LoadResponse {
        val doc = app.get(showUrl, headers = browserHeaders).document
        val title = cleanDubTitle(og(doc, "title") ?: doc.title())
        val poster = og(doc, "image")
        val episodes = buildDubEpisodes(dubEpisodeLinks(doc), poster)
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")
        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = og(doc, "description")
        }
    }

    // ------------------------------------------------------------------
    // Playback: decode the post page's watch payload, map name+id to
    // embed URLs, then dig / extract / relay in that order.
    // ------------------------------------------------------------------

    /**
     * The post page's watch anchor carries base64 JSON:
     * {servers: [{name, id}], type, backUrl, …}. Returns (name, id) rows.
     */
    private fun extractServers(postHtml: String): List<Pair<String, String>> {
        val raw = Regex("""qesen\.net/watch\?post=([A-Za-z0-9+/=_%\-]+)""")
            .find(postHtml)?.groupValues?.get(1) ?: return emptyList()
        var b64 = raw.replace("%2B", "+").replace("%2F", "/").replace("%3D", "=")
            .replace('-', '+').replace('_', '/')
            .filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        while (b64.length % 4 != 0) b64 += "="
        val jsonText = runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("servers") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nm = o.optString("name")
            val id = o.optString("id")
            if (nm.isNotBlank() && id.isNotBlank()) out += nm to id
        }
        return out
    }

    /** The operator's fixed name → embed template table. */
    private fun embedFor(name: String, id: String): String? = when (name.trim()) {
        "Arab HD" -> "https://arabhd.onl/embed-$id.html"
        "estream" -> "https://arabveturk.com/embed-$id.html"
        "Red HD" -> "https://iplayerhls.com/e/$id"
        "now" -> "https://extreamnow.org/embed-$id.html"
        "box" -> "https://youdboox.com/embed-$id.html"
        "Pro HD" -> "https://w.larhu.website/play.php?id=$id"
        "pro" -> "https://mdna.upns.online/#$id"
        "facebook" -> "https://app.videas.fr/embed/media/$id"
        "ok" -> "https://ok.ru/videoembed/$id"
        "dailymotion" -> "https://www.dailymotion.com/embed/video/$id"
        "youtube_in" -> "https://www.youtube.com/embed/$id"
        "youtube" -> "https://www.youtube.com/watch?v=$id"
        else -> id.takeIf { it.startsWith("http") }
    }

    /** Hosts whose players are dug on the phone before any extractor. */
    private val digHosts = listOf(
        "arabhd.onl", "arabveturk.com", "iplayerhls.com", "extreamnow.org",
        "youdboox.com", "larhu.website", "upns.online", "videas.fr"
    )

    private val digPriority = listOf(
        "Arab HD", "Red HD", "estream", "now", "box", "Pro HD", "pro", "facebook", "express"
    )

    private fun isExtractorFirst(name: String): Boolean =
        name.trim() in listOf("ok", "dailymotion", "youtube_in", "youtube")

    // ------------------------------------------------------------------
    // House helper set (ported from the proven providers)
    // ------------------------------------------------------------------

    private fun pageHeaders(ua: String?, referer: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua?.ifBlank { null } ?: browserUA
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        return h
    }

    private fun asciiSafe(url: String): String {
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 128 && (c.isLetterOrDigit() || c in "-._~:/?#[]@!$&'()*+,;=%")) sb.append(c)
            else sb.append('%').append(v.toString(16).uppercase().padStart(2, '0'))
        }
        return sb.toString()
    }

    private fun hostOf(url: String): String =
        Regex("""^[a-z]+://([^/]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun unescapeSlashes(text: String): String {
        var t = text
        var guard = 0
        while (t.contains("\\/") && guard < 5) {
            t = t.replace("\\/", "/")
            guard++
        }
        return t
    }

    /** All direct media URLs in the text, in order, deduped (cap 4). */
    private fun extractAllDirects(text: String): List<String> {
        val clean = unescapeSlashes(text)
        val out = LinkedHashSet<String>()
        fun add(u: String) {
            val t = u.trimEnd('\\')
            if (t.isNotBlank()) out += t
        }
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(clean).forEach {
            add(it.groupValues[1])
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").findAll(clean).forEach {
            add(it.groupValues[1])
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean).forEach { add(it.value) }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean).forEach { add(it.value) }
        Regex("""["'](//[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)["']""").findAll(clean).forEach {
            add("https:" + it.groupValues[1])
        }
        return out.take(4)
    }

    // Dean Edwards packer unpacker (same proven port as Brestj/MyCima/FasselHD).
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
     * Streaming probe of a candidate direct URL: reads AT MOST 512
     * bytes. 1 = looks like media, 0 = provably not, -1 = undecided.
     */
    private fun probeMedia(
        url: String,
        referer: String?,
        ua: String?,
        timeoutMs: Int = 5000
    ): Int {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", ua?.ifBlank { null } ?: browserUA)
            conn.setRequestProperty("Accept", "*/*")
            if (!referer.isNullOrBlank()) {
                conn.setRequestProperty("Referer", asciiSafe(referer))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code == 403 || code == 405 || code == 501) -1 else 0
            } else {
                val ctype = (conn.contentType ?: "").lowercase()
                when {
                    ctype.contains("mpegurl") || ctype.contains("x-mpegurl") -> {
                        val head = conn.inputStream.bufferedReader().readText().take(64)
                        if (head.contains("#EXTM3U")) 1 else 0
                    }
                    ctype.startsWith("video/") || ctype.startsWith("audio/") ||
                        ctype.contains("octet-stream") -> 1
                    ctype.contains("html") -> 0
                    url.contains(".m3u8") -> {
                        val head = conn.inputStream.bufferedReader().readText().take(64)
                        if (head.contains("#EXTM3U")) 1 else 0
                    }
                    else -> {
                        val bytes = ByteArray(512)
                        val n = conn.inputStream.read(bytes)
                        if (n <= 0) {
                            -1
                        } else {
                            val head = String(bytes, 0, n, Charsets.ISO_8859_1)
                            when {
                                head.contains("#EXTM3U") -> 1
                                head.contains("<html", ignoreCase = true) ||
                                    head.contains("<!DOCTYPE", ignoreCase = true) -> 0
                                head.length >= 12 && head.substring(4, 8) == "ftyp" -> 1
                                head.contains("moov") || head.contains("mdat") -> 1
                                else -> -1
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            -1
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private data class DugCandidate(
        val label: String,
        val url: String,
        val referer: String?
    )

    /** Parallel probes, ONE representative per host; siblings inherit the verdict. */
    private fun probeCandidates(
        cands: List<DugCandidate>,
        wallMs: Long = 5000
    ): List<DugCandidate> {
        if (cands.isEmpty()) return emptyList()
        val reps = LinkedHashMap<String, DugCandidate>()
        for (c in cands) reps.putIfAbsent(hostOf(c.url), c)
        val pool = Executors.newFixedThreadPool(minOf(reps.size, 5).coerceAtLeast(1))
        try {
            val futures = reps.mapValues { (_, rep) ->
                pool.submit(Callable { probeMedia(rep.url, rep.referer, null, 4000) })
            }
            val verdictByHost = HashMap<String, Int>()
            for ((host, fut) in futures) {
                verdictByHost[host] = runCatching {
                    fut.get(maxOf(500L, wallMs - 500), TimeUnit.MILLISECONDS)
                }.getOrDefault(-1)
            }
            return cands.filter { verdictByHost[hostOf(it.url)] != 0 }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Phone-side dig for ALL direct media URLs inside an embed page:
     * plain scan, packed-JS unpack + scan, then one iframe chase.
     */
    private suspend fun digAllForDirects(url: String, referer: String?): List<String> {
        val doc = runCatching {
            app.get(url, headers = pageHeaders(null, referer ?: mainUrl)).text
        }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        out += extractAllDirects(doc)
        for (iframe in Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""").findAll(doc)) {
            val src = iframe.groupValues[1]
            if (src.contains(".m3u8") || src.contains(".mp4")) out += src
        }
        if (out.isEmpty()) {
            for (unpacked in unpackPackedJs(doc)) {
                out += extractAllDirects(unpacked)
                if (out.isNotEmpty()) break
            }
        }
        if (out.isEmpty()) {
            val src = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                .find(doc)?.groupValues?.get(1)
            if (!src.isNullOrBlank() && !src.startsWith("about:") &&
                (src.startsWith("http") || src.startsWith("//"))
            ) {
                val abs = if (src.startsWith("//")) "https:$src" else src
                val inner = runCatching {
                    app.get(abs, headers = pageHeaders(null, url)).text
                }.getOrNull()
                if (inner != null) {
                    out += extractAllDirects(inner)
                    if (out.isEmpty()) {
                        for (unpacked in unpackPackedJs(inner)) {
                            out += extractAllDirects(unpacked)
                            if (out.isNotEmpty()) break
                        }
                    }
                }
            }
        }
        return out.take(3)
    }

    private suspend fun emitDirect(
        label: String,
        directUrl: String,
        referer: String?,
        ua: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val isHls = directUrl.contains(".m3u8")
        val headers = LinkedHashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = asciiSafe(referer)
        headers["User-Agent"] = ua?.ifBlank { null } ?: browserUA
        runCatching {
            callback(
                newExtractorLink(
                    name,
                    label,
                    directUrl,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer?.let { asciiSafe(it) } ?: ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

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

    private suspend fun resolveViaRelay(
        embed: String,
        referer: String?,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val relayUrl = "$relayBase/relay/resolve?url=" +
            java.net.URLEncoder.encode(embed, "UTF-8") +
            "&ref=" + java.net.URLEncoder.encode(referer ?: mainUrl, "UTF-8")
        var text: String? = null
        for (attempt in 1..2) {
            text = runCatching { app.get(relayUrl).text }.getOrNull()
            if (text != null) break
            Thread.sleep(1500)
        }
        val body = text ?: return 0
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return 0
        if (!json.optBoolean("ok")) return 0
        val resArr = json.optJSONArray("direct") ?: return 0
        var emitted = 0
        for (i in 0 until resArr.length()) {
            val item = resArr.optJSONObject(i) ?: continue
            val link = item.optString("url")
            if (link.isNotBlank() && probeMedia(link, embed, null) != 0) {
                emitDirect(label, link, embed, null, callback)
                emitted++
            }
        }
        return emitted
    }

    // ------------------------------------------------------------------
    // Dubbed lane playback (k.3chk.net): the episode page's shortlink
    // gives the post id; player slots live at /embed/{n}/{pid}/2/ and
    // answer only with the episode page as referer. Filled slots hold
    // an iframe to the real host embed (miravd / mwdy in the census),
    // which is dug on the phone with the /embed/ page as referer —
    // the same frame chain a browser runs. Per-host outcome codes go
    // into the DEBUG line (cf = Cloudflare challenge page, disabled =
    // the host's "Embeds disabled" gate, nodirect = player HTML but
    // no direct found, err = fetch failed).
    // ------------------------------------------------------------------

    private suspend fun digDubHost(
        embedUrl: String,
        embedPageUrl: String
    ): Pair<List<String>, String> {
        val body = runCatching {
            app.get(embedUrl, headers = pageHeaders(null, embedPageUrl)).text
        }.getOrNull() ?: return emptyList<String>() to "err"
        if (body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com")
        ) return emptyList<String>() to "cf"
        if (body.contains("Embeds disabled", ignoreCase = true)) {
            return emptyList<String>() to "disabled"
        }
        val out = LinkedHashSet<String>()
        out += extractAllDirects(body)
        if (out.isEmpty()) {
            for (unpacked in unpackPackedJs(body)) {
                out += extractAllDirects(unpacked)
                if (out.isNotEmpty()) break
            }
        }
        if (out.isEmpty()) {
            val src = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                .find(body)?.groupValues?.get(1)
            if (!src.isNullOrBlank() && !src.startsWith("about:") &&
                (src.startsWith("http") || src.startsWith("//"))
            ) {
                val abs = if (src.startsWith("//")) "https:$src" else src
                val inner = runCatching {
                    app.get(abs, headers = pageHeaders(null, embedUrl)).text
                }.getOrNull()
                if (inner != null) {
                    out += extractAllDirects(inner)
                    if (out.isEmpty()) {
                        for (unpacked in unpackPackedJs(inner)) {
                            out += extractAllDirects(unpacked)
                            if (out.isNotEmpty()) break
                        }
                    }
                }
            }
        }
        return out.take(3) to if (out.isEmpty()) "nodirect" else "ok${out.size}"
    }

    private suspend fun loadLinksDub(
        data: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt
        val diag = ArrayList<String>()
        diag += "QessetDUB"
        var emitted = 0

        val html = runCatching {
            app.get(data, headers = browserHeaders).text
        }.getOrNull() ?: run {
            diag += "page=ERR"
            emitDebug(diag, callback)
            return
        }
        val pid = Regex("""rel=["']shortlink["'] href=["'][^"']*\?p=(\d+)""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""\?p=(\d+)""").find(html)?.groupValues?.get(1)
            ?: run {
                diag += "pid=NONE"
                emitDebug(diag, callback)
                return
            }
        diag += "pid=$pid"

        // Enumerate player slots (census: 2 filled, rest empty).
        val typeFlag = if (data.contains("/video/movies/")) "1" else "2"
        val slots = ArrayList<Pair<String, String>>() // embedPageUrl → hostEmbedUrl
        var emptyStreak = 0
        for (n in 1..6) {
            if (elapsed() > 10000) break
            val embedPage = "$dubBase/embed/$n/$pid/$typeFlag/"
            val pageHtml = runCatching {
                app.get(embedPage, headers = pageHeaders(null, data)).text
            }.getOrNull()
            val src = pageHtml?.let {
                Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""")
                    .find(it)?.groupValues?.get(1)
            }?.ifBlank { null }
            if (src == null) {
                emptyStreak++
                if (n >= 2 && emptyStreak >= 2) break
            } else {
                emptyStreak = 0
                slots += embedPage to src
            }
        }
        diag += "srv=${slots.size}"

        // Dig each host embed on the phone (frame referer chain).
        val dug = ArrayList<DugCandidate>()
        val digCodes = ArrayList<String>()
        for ((embedPage, hostEmbed) in slots) {
            if (elapsed() > 16000) break
            val (links, code) = runCatching { digDubHost(hostEmbed, embedPage) }
                .getOrDefault(emptyList<String>() to "err")
            digCodes += "${hostOf(hostEmbed)}:$code"
            for (link in links) {
                dug += DugCandidate("مدبلج — ${hostOf(hostEmbed)}", link, hostEmbed)
            }
        }
        diag += "dig=[${digCodes.joinToString(",")}]"
        for (cand in probeCandidates(dug)) {
            if (emitted >= 3) break
            emitDirect(cand.label, cand.url, cand.referer, null, callback)
            emitted++
        }
        diag += "t=${elapsed() / 1000}s"
        if (emitted == 0) emitDebug(diag, callback)
    }

    // ------------------------------------------------------------------
    // loadLinks — data is the post URL (episode or movie).
    //
    // Dubbed (3chk) data goes to loadLinksDub. Otherwise:
    // Phase A: dig the operator's custom HLS hosts on the phone in
    //   priority order (Arab HD, Red HD, estream, …), candidates get
    //   one parallel per-host probe round; stop at 3 dug links / 9 s.
    // Phase B: app extractors for ok / dailymotion / youtube rows and
    //   anything the dig missed, until 5 links total or 14 s.
    // Phase C: relay resolve for leftover non-custom rows (cap 2).
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("3chk.net")) {
            loadLinksDub(data, callback)
            return true
        }
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt
        var emitted = 0
        val diag = ArrayList<String>()

        val html = runCatching {
            app.get(data, headers = browserHeaders).text
        }.getOrNull() ?: run {
            emitDebug(listOf("srv=ERR"), callback)
            return true
        }
        data class Row(val srvName: String, val embed: String)
        val rows = ArrayList<Row>()
        for ((srvName, id) in extractServers(html)) {
            val embed = embedFor(srvName, id) ?: continue
            rows += Row(srvName, embed)
        }
        diag += "srv=${rows.size}"

        // Phase A — phone digs of the custom HLS hosts, priority order.
        val dug = ArrayList<DugCandidate>()
        val dugEmbeds = HashSet<String>()
        val ordered = rows.sortedBy { row ->
            val idx = digPriority.indexOf(row.srvName.trim())
            if (idx >= 0) idx else 99
        }
        for (row in ordered) {
            if (elapsed() > 9000 || dug.size >= 6) break
            val host = hostOf(row.embed)
            val isDig = digHosts.any { host.contains(it) } ||
                (!isExtractorFirst(row.srvName) && hostOf(row.embed) !in
                    listOf("ok.ru", "www.dailymotion.com", "www.youtube.com"))
            if (!isDig) continue
            val links = runCatching { digAllForDirects(row.embed, data) }
                .getOrDefault(emptyList())
            for (link in links) {
                dug += DugCandidate(row.srvName, link, row.embed)
                dugEmbeds += row.embed
            }
        }
        var digOk = 0
        for (cand in probeCandidates(dug)) {
            if (emitted >= 3) break
            emitDirect(cand.label, cand.url, cand.referer, null, callback)
            emitted++
            digOk++
        }
        diag += "dig=$digOk"

        // Phase B — extractors for the rest.
        var extOk = 0
        for (row in rows) {
            if (emitted >= 5 || elapsed() > 14000) break
            if (row.embed in dugEmbeds && digOk > 0) continue
            val before = emitted
            runCatching {
                loadExtractor(row.embed, data, subtitleCallback) { link ->
                    emitted++
                    callback(link)
                }
            }
            if (emitted > before) extOk++
        }
        diag += "ext=$extOk"

        // Phase C — relay resolve for leftover non-custom rows.
        var relayOk = 0
        if (emitted == 0) {
            for (row in rows) {
                if (relayOk >= 2 || elapsed() > 22000) break
                if (digHosts.any { hostOf(row.embed).contains(it) }) continue
                val n = runCatching {
                    resolveViaRelay(row.embed, data, row.srvName, callback)
                }.getOrDefault(0)
                if (n > 0) {
                    relayOk++
                    emitted += n
                }
            }
        }
        diag += "relay=$relayOk"
        diag += "t=${elapsed() / 1000}s"

        if (emitted == 0) emitDebug(diag, callback)
        return true
    }
}
