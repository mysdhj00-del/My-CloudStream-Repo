package com.horror

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * HorrorMovies (أفلام رعب) — horror movies aggregated from the four
 * strongest horror lanes measured live 2026-10-25, MOVIES ONLY:
 *
 * - MOVIZLAND (moviz.land): horror lives under the TAG «رعب»
 *   (1,331 posts). Tag archives /tag/رعب/page/{n}/ render
 *   .archive-card x30 with data-title/url/image. Watch page
 *   {post}/watch/ lists .server-card embeds (extractors + digs +
 *   upbom direct mp4s). Post pages carry a real rating:
 *   <span class="rating">7.2/10</span> — shown on the detail page.
 * - EGYBEST (egibest.life): /genre/رعب/ renders a.postBlockCol
 *   cards (title attr, img[data-src] poster). Title pages carry
 *   JSON-LD embedUrl + loadIframe(this, '<site>/watch/?url=<b64>')
 *   server wrappers + plain iframes; chain = extractors → phone
 *   dig (raw + packed-JS) → relay.
 * - MYCIMA (vid.mycima.cc): Melody horror category
 *   categories-cimawbas.php?cat=horror-movies (&page={n}),
 *   ul#pm-grid cards linking watch.php?vid={vid}; play.php
 *   lists the embeds (iframe srcs + /e/ + /embed/ URLs);
 *   chain = extractors → digs → relay.
 * - LAROSTV (larozatv.com): /category/رعب renders article.ay-card
 *   (movie-badged cards only). CATEGORY pages paginate with ?page=N
 *   (NOT /page/N — the /page/N path returns a stale 10-card set;
 *   ?page=2/?page=3 verified 20 NEW cards each, zero overlap).
 *   (movie-badged cards only); movie posts open the servers page
 *   /watch/{slug}/servers with [data-server-url] buttons (19 per
 *   title on the confirmed v2 chain: MixDrop-first + early stop).
 *
 * SPEED (his standing order): every loadLinks runs on a wall-clock
 * budget — extractors fastest-first, capped links, digs capped,
 * relay only when nothing playable exists yet.
 *
 * RATING: CloudStream's card type has no rating slot in this app
 * version, so ratings ride the detail page: the plot line opens
 * with «★ التقييم: X/10» wherever the source page provides one
 * (MovizLand verified; other lanes as their pages provide).
 */
class HorrorMoviesProvider : MainAPI() {
    override var mainUrl = "https://www.moviz.land"
    override var name = "أفلام رعب"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )
    private val relayBase = "https://egydead-stremio.onrender.com"

    override val mainPage = mainPageOf(
        "moviz:https://www.moviz.land/tag/%d8%b1%d8%b9%d8%a8/" to
            "أفلام رعب — موفيز لاند",
        "eg:https://egibest.life/genre/%d8%b1%d8%b9%d8%a8/" to
            "أفلام رعب — إيجي بست",
        "myc:https://vid.mycima.cc/categories-cimawbas.php?cat=horror-movies" to
            "أفلام رعب — ماي سيما",
        "lar:https://larozatv.com/category/%D8%B1%D8%B9%D8%A8" to
            "أفلام رعب — لاروز",
        "top:https://topcinema.film/genre/%D8%B1%D8%B9%D8%A8/" to
            "أفلام رعب — توب سينما",
        "af:https://aflamstream.com/category/%D8%A3%D9%81%D9%84%D8%A7%D9%85-%D8%B1%D8%B9%D8%A8-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9-%D9%88%D8%AC%D8%AF%D9%8A%D8%AF%D8%A9/" to
            "أفلام رعب — أفلام ستريم"
    )

    private fun cleanHtml(s: String): String =
        runCatching { Jsoup.parse(s).text() }.getOrDefault(s).trim()

    private fun cleanMovieTitle(raw: String): String {
        var t = cleanHtml(raw).removePrefix("مشاهدة").trim()
            .removePrefix("فيلم").trim()
        t = t.replace(Regex("""\s*[-|]\s*(MovizLand|موفيز لاند|Egybest|ايجى بست|MyCima|ماي سيما|LaRosTV|لاروز|توب سينما|TopCinema|سلسلة افلام الرعب|أفلام ستريم).*${'$'}""", RegexOption.IGNORE_CASE), "").trim()
        return t
    }

    private fun movieCard(title: String, url: String, poster: String?): SearchResponse? {
        val name = cleanMovieTitle(title)
        if (name.isBlank() || url.isBlank()) return null
        return newMovieSearchResponse(name, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun isMovieTitle(title: String): Boolean {
        val t = cleanHtml(title)
        return !t.contains("مسلسل") && !Regex("""الحلقة\s*\d+""").containsMatchIn(t)
    }

    // ---------------------------------------------------------------
    // Home
    // ---------------------------------------------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (lane, base) = request.data.split(":", limit = 2)
        val url = when (lane) {
            "moviz" -> if (page <= 1) base else base + "page/$page/"
            "eg" -> if (page <= 1) base else base + "page/$page/"
            "top" -> if (page <= 1) base else base + "page/$page/"
            "af" -> if (page <= 1) base else base + "page/$page/"
            "myc" -> if (page <= 1) base else base + "&page=$page"
            "lar" -> if (page <= 1) base else base + "?page=$page"
            else -> base
        }
        val doc = runCatching {
            app.get(url, headers = browserHeaders).document
        }.getOrNull() ?: return newHomePageResponse(emptyList(), false)
        val cards = when (lane) {
            "moviz", "eg", "myc", "lar", "top", "af" -> parseLaneCards(lane, doc)
            else -> emptyList()
        }
        val hasNext = when (lane) {
            "moviz", "eg", "top", "af" -> doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
            "myc" -> doc.select("a[href*=page=${page + 1}]").isNotEmpty()
            "lar" -> doc.select("a[href]").any {
                it.attr("href").contains("?page=${page + 1}")
            }
            else -> false
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
            hasNext
        )
    }

    private fun parseLaneCards(lane: String, doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        when (lane) {
            "moviz" -> for (el in doc.select(".archive-card")) {
                movieCard(
                    el.attr("data-title"),
                    el.attr("data-url"),
                    el.attr("data-image").ifBlank { null }
                )?.let { out += it }
            }
            "eg" -> for (a in doc.select("a.postBlockCol")) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val img = a.selectFirst("img")
                val poster = img?.attr("data-src")
                    ?.takeIf { it.isNotBlank() && !it.contains("src-default") }
                    ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                val title = a.attr("title").ifBlank {
                    a.selectFirst("h3.title")?.text().orEmpty()
                }
                movieCard(title, href, poster)?.let { out += it }
            }
            "myc" -> for (li in doc.select("ul#pm-grid li")) {
                val a = li.selectFirst("a[href*=watch.php]") ?: continue
                val href = a.attr("href")
                val title = a.attr("title")
                val img = li.selectFirst("img")
                val poster = img?.attr("data-echo")?.ifBlank { null }
                    ?: img?.attr("src")?.ifBlank { null }
                movieCard(title, href, poster)?.let { out += it }
            }
            "lar" -> for (article in doc.select("article.ay-card")) {
                val a = article.selectFirst("a.ay-card-media")
                    ?: article.selectFirst("a[href]") ?: continue
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val badge = article.selectFirst(".ay-card-badge")?.text().orEmpty()
                if (badge.isNotBlank() && !badge.contains("فيلم") &&
                    !badge.contains("نسخة")
                ) continue
                val title = article.selectFirst(".ay-card-title")?.text()
                    ?: a.attr("aria-label")
                val poster = article.selectFirst("img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("src") }
                }?.ifBlank { null }
                movieCard(title, href, poster)?.let { out += it }
            }
            "top" -> for (a in doc.select("a.recent--block")) {
                val href = a.attr("href")
                val title = a.attr("title")
                if (!isMovieTitle(title)) continue
                val img = a.selectFirst("img")
                val poster = img?.attr("data-src")?.ifBlank { null }
                    ?: img?.attr("src")?.ifBlank { null }
                val rating = a.selectFirst("li.imdbRating")?.text()?.trim()
                val shown = if (!rating.isNullOrBlank()) {
                    cleanMovieTitle(title) + " ★ $rating"
                } else {
                    title
                }
                movieCard(shown, href, poster)?.let { out += it }
            }
            "af" -> for (div in doc.select("div.ht_grid_1_4")) {
                val a = div.selectFirst("a.thumbnail-link") ?: continue
                val href = a.attr("href")
                val img = div.selectFirst("img")
                val poster = img?.attr("src")?.ifBlank { null }
                val title = div.selectFirst("h2.entry-title a")?.text().orEmpty()
                movieCard(title, href, poster)?.let { out += it }
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // Search (all four lanes, capped per lane)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        val jobs = listOf(
            "https://www.moviz.land/?s=$q" to "moviz",
            "https://egibest.life/?s=$q" to "eg",
            "https://vid.mycima.cc/search.php?keywords=$q" to "myc",
            "https://larozatv.com/search?q=$q" to "lar",
            "https://topcinema.film/?s=$q" to "top",
            "https://aflamstream.com/?s=$q" to "af"
        )
        for ((url, lane) in jobs) {
            runCatching {
                val doc = app.get(url, headers = browserHeaders).document
                val cards = when (lane) {
                    "moviz" -> doc.select(".search-card").mapNotNull { card ->
                        val a = card.selectFirst("a[href]") ?: return@mapNotNull null
                        val img = card.selectFirst("img")
                        val title = card.selectFirst(".search-card-title")?.text()
                            ?: img?.attr("alt") ?: a.attr("aria-label")
                        movieCard(title, a.attr("href"), img?.attr("src"))
                    }
                    "eg" -> doc.select("a.postBlock, a.postBlockCol").mapNotNull { a ->
                        val img = a.selectFirst("img")
                        val poster = img?.attr("data-src")
                            ?.takeIf { it.isNotBlank() && !it.contains("src-default") }
                            ?: img?.attr("src")
                        val title = a.attr("title").ifBlank {
                            a.selectFirst("h3.title")?.text().orEmpty()
                        }
                        movieCard(title, a.attr("href"), poster)
                    }
                    "myc" -> doc.select("ul#pm-grid li").mapNotNull { li ->
                        val a = li.selectFirst("a[href*=watch.php]") ?: return@mapNotNull null
                        val img = li.selectFirst("img")
                        val poster = img?.attr("data-echo")?.ifBlank { null }
                            ?: img?.attr("src")?.ifBlank { null }
                        movieCard(a.attr("title"), a.attr("href"), poster)
                    }
                    "lar" -> doc.select("article.ay-card").mapNotNull { article ->
                        val a = article.selectFirst("a.ay-card-media")
                            ?: article.selectFirst("a[href]") ?: return@mapNotNull null
                        val badge = article.selectFirst(".ay-card-badge")?.text().orEmpty()
                        if (badge.isNotBlank() && !badge.contains("فيلم") &&
                            !badge.contains("نسخة")
                        ) return@mapNotNull null
                        val title = article.selectFirst(".ay-card-title")?.text()
                            ?: a.attr("aria-label")
                        val poster = article.selectFirst("img")?.let {
                            it.attr("abs:src").ifBlank { it.attr("src") }
                        }?.ifBlank { null }
                        movieCard(title, a.attr("abs:href").ifBlank { a.attr("href") }, poster)
                    }
                    "top" -> doc.select("a.recent--block").mapNotNull { a ->
                        val title = a.attr("title")
                        if (!isMovieTitle(title)) return@mapNotNull null
                        val img = a.selectFirst("img")
                        val poster = img?.attr("data-src")?.ifBlank { null }
                            ?: img?.attr("src")?.ifBlank { null }
                        val rating = a.selectFirst("li.imdbRating")?.text()?.trim()
                        val shown = if (!rating.isNullOrBlank()) {
                            cleanMovieTitle(title) + " ★ $rating"
                        } else {
                            title
                        }
                        movieCard(shown, a.attr("href"), poster)
                    }
                    "af" -> doc.select("div.ht_grid_1_4").mapNotNull { div ->
                        val a = div.selectFirst("a.thumbnail-link") ?: return@mapNotNull null
                        val img = div.selectFirst("img")
                        val title = div.selectFirst("h2.entry-title a")?.text().orEmpty()
                        movieCard(title, a.attr("href"), img?.attr("src"))
                    }
                    else -> emptyList()
                }
                out += cards.filter { isMovieTitle(it.name) }.take(12)
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------
    private fun og(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=og:$prop]")?.attr("content")
            ?.trim()?.ifBlank { null }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("moviz.land") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(
                    og(doc ?: return movieFallback(url), "title") ?: ""
                )
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val rating = doc?.let {
                    Regex("""class="rating"[^>]*>\s*<i[^>]*></i>\s*([\d.]+)/10""")
                        .find(it.html())?.groupValues?.get(1)
                }
                val plot = buildString {
                    if (rating != null) append("★ التقييم: $rating/10\n")
                    if (!desc.isNullOrBlank()) append(desc)
                }.ifBlank { null }
                newMovieLoadResponse(title.ifBlank { "MovizLand" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = plot
                }
            }
            url.contains("egibest") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                newMovieLoadResponse(title.ifBlank { "EgyBest" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                }
            }
            url.contains("mycima") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                    ?: doc?.selectFirst("img[src*=uploads]")?.attr("src")
                val desc = doc?.let { og(it, "description") }
                newMovieLoadResponse(title.ifBlank { "MyCima" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                }
            }
            url.contains("larozatv") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val slug = url.trimEnd('/').substringAfterLast('/')
                val servers = "https://larozatv.com/watch/$slug/servers"
                newMovieLoadResponse(title.ifBlank { "LaRosTV" }, url, TvType.Movie, subData(servers, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                }
            }
            url.contains("topcinema") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val watch = url.trimEnd('/') + "/watch/"
                newMovieLoadResponse(title.ifBlank { "TopCinema" }, url, TvType.Movie, subData(watch, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                }
            }
            url.contains("aflamstream") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                newMovieLoadResponse(title.ifBlank { "AflamStream" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                }
            }
            else -> movieFallback(url)
        }
    }

    private suspend fun movieFallback(url: String): LoadResponse {
        val name = cleanMovieTitle(url.substringAfterLast('/').replace('-', ' '))
        return newMovieLoadResponse(name.ifBlank { "فيلم" }, url, TvType.Movie, subData(url, name))
    }

    // ---------------------------------------------------------------
    // Playback — shared machinery
    // ---------------------------------------------------------------
    private fun fixUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "https://" + u.trimStart('/')
        else -> u
    }

    private fun asciiSafe(url: String): String {
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 128 && (c.isLetterOrDigit() || c in "-._~:/?#[]@!$&'()*+,;=%")) {
                sb.append(c)
            } else {
                sb.append('%').append(v.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    private fun hostOf(url: String): String =
        Regex("""^[a-z]+://([^/]+)""").find(url)?.groupValues?.get(1)
            ?.lowercase() ?: ""

    private fun decodeBase64(input: String): String? {
        val clean = input.trim().replace('-', '+').replace('_', '/')
            .filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = table.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return runCatching {
            String(out.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun extractDirect(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(clean)?.let {
            return it.value
        }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(clean)?.let {
            return it.value
        }
        return null
    }

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
            out += Regex("""\b\w+\b""").replace(payload) { mr ->
                map[mr.value] ?: mr.value
            }
        }
        return out
    }

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

    private suspend fun resolveViaRelay(
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val relayUrl = "$relayBase/relay/resolve?url=" +
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

    private suspend fun digAllForDirects(
        url: String,
        referer: String?
    ): List<String> {
        val doc = runCatching {
            val h = LinkedHashMap<String, String>()
            h["User-Agent"] = browserUA
            if (!referer.isNullOrBlank()) h["Referer"] = asciiSafe(referer)
            app.get(url, headers = h).text
        }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        out += extractAllDirects(doc)
        if (out.isEmpty()) {
            for (unpacked in unpackPackedJs(doc)) {
                out += extractAllDirects(unpacked)
                if (out.isNotEmpty()) break
            }
        }
        return out.take(3)
    }

    private fun extractAllDirects(text: String): List<String> {
        val clean = text.replace("\\/", "/")
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
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean).forEach {
            add(it.value)
        }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean).forEach {
            add(it.value)
        }
        return out.take(4)
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            callback(
                newExtractorLink(
                    name, name, url,
                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitDirectLabeled(
        label: String,
        directUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = LinkedHashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = asciiSafe(referer)
        headers["User-Agent"] = browserUA
        runCatching {
            callback(
                newExtractorLink(
                    name, label, directUrl,
                    if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer?.let { asciiSafe(it) } ?: ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

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

    // ---------------------------------------------------------------
    // Playback — per-lane chains, all on a wall-clock budget
    // ---------------------------------------------------------------
    private val priorityHosts = listOf(
        "mixdrop", "hgcloud", "streamhg", "vidmoly", "byse", "doodstream",
        "streamwish", "uqload", "voe", "filemoon", "vidhide", "earnvids"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        // Auto English subtitles first (settings switch, default ON).
        val (realData, subTitle, _) = parseSubData(data)
        if (HorrorSubs.autoEnglish && subTitle.isNotBlank()) {
            emitEnglishSub(subTitle, subtitleCallback)
        }
        return when {
            realData.contains("moviz.land") -> loadLinksMoviz(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("egibest") -> loadLinksEgyBest(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("mycima") -> loadLinksMyCima(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("larozatv") -> loadLinksLaRos(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("topcinema") -> loadLinksTop(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("aflamstream") -> loadLinksAf(realData, subtitleCallback, callback, ::elapsed)
            else -> {
                emitDebug(listOf("Horror", "unknown-lane"), callback)
                true
            }
        }
    }

    /** Shared extractor → dig → relay core with caps and a budget. */
    private suspend fun runChain(
        embeds: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long,
        lane: String,
        allowDebug: Boolean = true
    ): Boolean {
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        var extOk = 0
        val unresolved = mutableListOf<String>()
        val ordered = embeds.sortedByDescending { e ->
            priorityHosts.any { e.contains(it) }
        }
        for (embed in ordered) {
            if (emitted >= 5 || elapsed() > 11000) break
            val before = emitted
            runCatching { loadExtractor(embed, referer, subtitleCallback, counting) }
            if (emitted > before) extOk++ else unresolved += embed
        }
        var digOk = 0
        if (emitted < 3) {
            for (embed in unresolved) {
                if (elapsed() > 16000 || digOk >= 3) break
                if (scanEmbed(embed, referer, counting)) digOk++
            }
        }
        var relayOk = 0
        if (emitted == 0) {
            for (embed in unresolved.take(3)) {
                if (elapsed() > 22000) break
                relayOk += resolveViaRelay(embed, referer, counting)
            }
        }
        if (emitted == 0 && allowDebug) {
            emitDebug(
                listOf("Horror·$lane", "srv=${embeds.size}", "ext=$extOk",
                    "dig=$digOk", "relay=$relayOk", "t=${elapsed() / 1000}s"),
                callback
            )
        }
        return true
    }

    private suspend fun loadLinksMoviz(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val watchUrl = data.trimEnd('/') + "/watch/"
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·Moviz", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashSet<String>()
        val upbom = mutableListOf<String>()
        for (card in doc.select(".server-card")) {
            val src = card.attr("data-src")
            if (!src.startsWith("http")) continue
            if (hostOf(src).contains("upbom")) upbom += src else embeds += src
        }
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        // upbom direct mp4s first (fast, own CDN).
        for (embed in upbom) {
            val text = runCatching {
                app.get(embed, headers = browserHeaders, referer = watchUrl).text
            }.getOrNull() ?: continue
            val byQ = LinkedHashMap<String, String>()
            Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").findAll(text).forEach { m ->
                val u = m.value.trimEnd('\\')
                val q = Regex("""(360|480|720|1080)p""").find(u)
                    ?.groupValues?.get(1) ?: "HD"
                byQ.putIfAbsent(q, u)
            }
            for ((q, u) in byQ) {
                emitDirectLabeled("موفيز لاند ${q}p", u, embed, counting)
            }
        }
        if (emitted >= 2) return true
        // Remaining servers through the shared chain (extractors/digs/relay).
        val rest = embeds.toList()
        if (rest.isNotEmpty()) {
            runChain(rest, watchUrl, subtitleCallback, counting, elapsed, "Moviz",
                allowDebug = emitted == 0)
        } else if (emitted == 0) {
            emitDebug(listOf("Horror·Moviz", "srv=0"), callback)
        }
        return true
    }

    private suspend fun loadLinksEgyBest(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val res = runCatching { app.get(data, headers = browserHeaders) }.getOrNull()
        if (res == null) {
            emitDebug(listOf("Horror·EgyBest", "page-fail"), callback)
            return true
        }
        val text = res.text
        val embeds = LinkedHashSet<String>()
        Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let {
            if (it.startsWith("http")) embeds += fixUrl(it)
        }
        Regex("""loadIframe\(\s*this\s*,\s*'([^']+)'\s*\)""").findAll(text).forEach { m ->
            val wrapper = m.groupValues[1]
            val encoded = wrapper.substringAfter("url=", "")
            if (encoded.isNotBlank()) {
                val decoded = decodeBase64(
                    runCatching {
                        java.net.URLDecoder.decode(encoded, "UTF-8")
                    }.getOrDefault(encoded)
                )
                if (decoded != null && decoded.startsWith("http")) {
                    embeds += fixUrl(decoded)
                } else if (wrapper.startsWith("http") &&
                    !wrapper.contains("egibest")
                ) {
                    embeds += fixUrl(wrapper)
                }
            }
        }
        res.document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("egibest")) {
                embeds += fixUrl(src)
            }
        }
        return runChain(embeds.toList(), data, subtitleCallback, callback, elapsed, "EgyBest")
    }

    private suspend fun loadLinksMyCima(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val vid = Regex("""vid=([a-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            emitDebug(listOf("Horror·MyCima", "vid-missing"), callback)
            return true
        }
        val playUrl = "https://vid.mycima.cc/play.php?vid=$vid"
        val res = runCatching {
            app.get(playUrl, headers = browserHeaders, referer = data)
        }.getOrNull()
        if (res == null) {
            emitDebug(listOf("Horror·MyCima", "play-fail"), callback)
            return true
        }
        val candidates = LinkedHashSet<String>()
        res.document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("mycima")) {
                candidates += fixUrl(src)
            }
        }
        res.document.select("a[href]").forEach {
            val href = it.attr("href").trim()
            if (href.startsWith("http") && !href.contains("mycima") &&
                (href.contains("/e/") || href.contains("/embed"))
            ) candidates += fixUrl(href)
        }
        Regex("""https?://[^\s"'<>\\]+""").findAll(res.text).forEach { m ->
            val u = m.value
            if (!u.contains("mycima") && (u.contains("/e/") || u.contains("/embed")) &&
                !u.contains("google") && !u.contains("facebook") &&
                !u.contains(".js") && !u.contains(".css") &&
                !u.contains(".png") && !u.contains(".jpg")
            ) candidates += fixUrl(u)
        }
        return runChain(candidates.toList(), data, subtitleCallback, callback, elapsed, "MyCima")
    }

    private suspend fun loadLinksLaRos(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val doc = runCatching {
            app.get(data, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·LaRos", "servers-fail"), callback)
            return true
        }
        val rows = ArrayList<String>()
        val perHost = HashMap<String, Int>()
        for (btn in doc.select("[data-server-url]")) {
            val u = btn.attr("data-server-url").trim()
            if (!u.startsWith("http")) continue
            val host = hostOf(u)
            perHost[host] = (perHost[host] ?: 0) + 1
            rows += u
        }
        // MixDrop-first (one fetch + unpack), then the rest, early stop.
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        val attempted = HashSet<String>()
        var mixTried = 0
        for (u in rows) {
            if (emitted >= 2 || elapsed() > 6000 || mixTried >= 3) break
            if (!hostOf(u).contains("mixdrop")) continue
            mixTried++
            attempted += u
            runCatching { loadExtractor(u, data, subtitleCallback, counting) }
        }
        val remaining = rows.filter { it !in attempted }
        if (emitted < 5) {
            runChain(remaining, data, subtitleCallback, counting, elapsed, "LaRos",
                allowDebug = emitted == 0)
        }
        return true
    }

    /**
     * AUTO ENGLISH SUBTITLES (v4): CloudStream has no standalone
     * subtitle-plugin type that auto-attaches to other providers'
     * playback, so the tool lives here — inside loadLinks, before
     * any video link is emitted:
     *   Latin title (+ year) → IMDb suggest (key-free) → IMDb id →
     *   OpenSubtitles v3 Stremio endpoint (key-free) → first
     *   lang=eng file → subtitleCallback(SubtitleFile("English", url)).
     * The .srt downloads without auth (verified: The Conjuring 2013,
     * 79 KB / 1087 cues; Breaking Bad S1E1, 8 English files).
     * Everything is fail-soft and fail-fast (8 s caps): if any step
     * misses, playback continues with zero subtitles — never blocked.
     * Titles with no Latin part (fully Arabic titles) cannot be
     * resolved by IMDb suggest and get no subtitle in v4.
     */
    private fun httpGetText(url: String, timeoutMs: Int): String? {
        return runCatching {
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", browserUA)
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

    private fun resolveImdbId(latinTitle: String, year: Int?): String? {
        val bucket = latinTitle.firstOrNull()?.lowercaseChar() ?: return null
        val q = java.net.URLEncoder.encode(latinTitle, "UTF-8")
        val json = httpGetText(
            "https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json", 8000
        ) ?: return null
        val arr = runCatching {
            JSONObject(json).optJSONArray("d")
        }.getOrNull() ?: return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val kind = o.optString("q")
            if (fallback == null && kind == "feature") fallback = id
            if (kind != "feature" && kind != "TV series") continue
            if (year != null && o.has("y") && o.optInt("y") != year) continue
            return id
        }
        return fallback
    }

    private suspend fun emitEnglishSub(
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        runCatching {
            val year = Regex("""(19|20)\d\d""").find(title)?.value?.toIntOrNull()
            for (candidate in latinTitleCandidates(title)) {
                val tt = resolveImdbId(candidate, year) ?: continue
                val json = httpGetText(
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json",
                    8000
                ) ?: continue
                val arr = runCatching {
                    JSONObject(json).optJSONArray("subtitles")
                }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("lang") != "eng") continue
                    val url = o.optString("url")
                    if (url.isNotBlank()) {
                        subtitleCallback(SubtitleFile("English", url))
                        return
                    }
                }
            }
        }
    }

    /** Packs title (+year, parsed from the title) into a loadLinks data string. */
    private fun subData(url: String, title: String): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return url + "|||" + enc
    }

    private fun parseSubData(data: String): Triple<String, String, String> {
        val parts = data.split("|||")
        val real = parts.getOrNull(0) ?: data
        val title = parts.getOrNull(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("")
        }.orEmpty()
        return Triple(real, title, "")
    }
    private fun httpPostForm(
        url: String,
        fields: Map<String, String>,
        referer: String
    ): String? {
        return runCatching {
            val body = fields.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(v, "UTF-8")
            }
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", browserUA)
            conn.setRequestProperty("Referer", asciiSafe(referer))
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded"
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            } ?: return@runCatching null
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    /**
     * TopCinema lane: watch page → li.server--item (data-id,
     * data-server) → POST Single/Server.php (Referer = watch page,
     * XHR) → iframe embed → shared chain. The active server's
     * iframe is also on the watch page itself (server 0).
     */
    private suspend fun loadLinksTop(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val watchUrl = data
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·Top", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashSet<String>()
        doc.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("topcinema")) {
                embeds += src
            }
        }
        var postId: String? = null
        val items = ArrayList<Pair<String, String>>()
        for (li in doc.select("li.server--item")) {
            val id = li.attr("data-id")
            val idx = li.attr("data-server")
            if (id.isNotBlank() && idx.isNotBlank()) {
                postId = id
                items += id to idx
            }
        }
        if (postId != null) {
            for ((id, idx) in items) {
                if (elapsed() > 12000) break
                val html = httpPostForm(
                    "https://topcinema.film/wp-content/themes/movies2023/Ajaxat/Single/Server.php",
                    mapOf("id" to id, "i" to idx),
                    watchUrl
                ) ?: continue
                val iframe = Regex("""<iframe[^>]*src="([^"]+)"""")
                    .find(html)?.groupValues?.get(1)
                if (iframe != null && iframe.startsWith("http") &&
                    !iframe.contains("topcinema")
                ) {
                    embeds += iframe
                }
            }
        }
        return runChain(embeds.toList(), watchUrl, subtitleCallback, callback, elapsed, "Top")
    }

    /**
     * AflamStream lane: post URL's trailing segment is the post id →
     * /watch/?id={id} → .server-btn[data-url] (5 servers) →
     * shared chain.
     */
    private suspend fun loadLinksAf(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val postId = data.trimEnd('/').substringAfterLast('/')
        if (postId.isBlank() || postId == data.trimEnd('/')) {
            emitDebug(listOf("Horror·Af", "id-missing"), callback)
            return true
        }
        val watchUrl = "https://aflamstream.com/watch/?id=$postId"
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·Af", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashSet<String>()
        doc.select(".server-btn[data-url]").forEach {
            val u = it.attr("data-url").trim()
            if (u.startsWith("http")) embeds += u
        }
        doc.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("aflamstream")) {
                embeds += src
            }
        }
        return runChain(embeds.toList(), watchUrl, subtitleCallback, callback, elapsed, "Af")
    }
}
