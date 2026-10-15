package com.isk

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
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * 3isk / Qessat Ashq (3iskk.xyz) provider for CloudStream 3.
 *
 * Built against the live structure verified on 2026-10-15:
 * - WordPress (custom "3skneew-vid" theme, DooPlay lineage). Content lives
 *   under /watch/movies/{slug}/, /watch/tvshows/{slug}/ and
 *   /watch/episodes/serie-{name}-season-{s}-episode-{e}/. Portals /w-mvs/,
 *   /w-srs/, /w-hlqat/ paginate WP-style with /page/{n}/; search is /?s={q}.
 * - Cards are a.type_item anchors carrying the title attribute; the poster
 *   is the child img's data-image (its src is a theme placeholder).
 * - Episode lists: episode AND tvshow pages render the series' episodes as
 *   a.ep-num anchors (href + data-ep-num + title), so load() builds the
 *   episode list from them; season/episode also parse from the slug.
 * - Playback chain (traced end-to-end, including a real-browser probe):
 *   the detail page itself carries NO player. With a session cookie from
 *   a warm GET of the detail page, GET {url}see/ returns the watch page:
 *   ul#player_servers li.server_nth items (data-post / data-nume /
 *   data-type) plus the default iframe#iframe_player whose src is
 *   {site}/embed/{nume}/{post}/{2 for tv, 1 for movie}/. That embed page
 *   (needs the session + the see-page as referer) nests the real host
 *   embed (e.g. ukrcdn.club/e/{uuid} — a host no built-in extractor
 *   knows and unreachable from datacenter IPs). loadLinks therefore runs
 *   three stages: built-in extractors, a phone-side dig for the direct
 *   file inside each rejected embed page, and the relay server's generic
 *   /relay/resolve dig. DEBUG markers ride along until the user's phone
 *   confirms which stage lands.
 * - Every request carries an Android-Chrome header set.
 */
class IskProvider : MainAPI() {
    override var mainUrl = "https://3iskk.xyz"
    override var name = "3isk"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الحلقات",
        "$mainUrl/w-hlqat/" to "الحلقات",
        "$mainUrl/w-srs/" to "المسلسلات",
        "$mainUrl/w-mvs/" to "الأفلام",
        "$mainUrl/genre/series-mudablij-121/" to "مسلسلات مدبلجة",
    )

    // ---------- helpers ----------

    private fun cleanTitle(raw: String): String = raw
        .trim()
        .removePrefix("مشاهدة")
        .trim()
        .replace(Regex("""\s*\|.*$"""), "")
        .trim()

    private fun isAnimeTitle(title: String): Boolean =
        title.contains("انمي") || title.contains("أنمي") || title.contains("كرتون")

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.contains("/watch/")) return null
        val title = cleanTitle(attr("title").ifBlank { text() })
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-image"))
            ?: fixUrlNull(img?.attr("data-src"))
            ?: fixUrlNull(img?.attr("src"))?.takeIf {
                !it.contains("dt_poster") && !it.contains("no-image") && !it.contains("blank")
            }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val isSeries = href.contains("/watch/tvshows/") || href.contains("/watch/episodes/") ||
            title.contains("مسلسل") || title.contains("الحلقة")
        return when {
            isSeries && isAnimeTitle(title) ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            isSeries ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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

    private fun org.jsoup.nodes.Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("a.type_item[href*=\"/watch/\"]").forEach { a ->
            val item = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            val existing = seen[item.url]
            if (existing == null || (existing.posterUrl == null && item.posterUrl != null)) {
                seen[item.url] = item
            }
        }
        return seen.values.toList()
    }

    /** season/episode numbers carried by an episode slug. */
    private fun slugNumbers(url: String): Pair<Int?, Int?> {
        val season = Regex("""season-(\d{1,2})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("""episode-(\d{1,4})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return season to episode
    }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) request.data else "$base/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val items = document.parseCards()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl + "/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        return app.get(url, headers = browserHeaders).document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
        val rawTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.title()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) throw ErrorLoadingException("No title")
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.trim()?.ifBlank { null }

        if (url.contains("/watch/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // Episodes render as a.ep-num anchors on episode and tvshow pages.
        val (hubSeason, _) = slugNumbers(url)
        val episodes = mutableListOf<Episode>()
        val seenEp = HashSet<String>()
        document.select("a.ep-num[href], a[href*=\"/watch/episodes/\"]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (!href.contains("/watch/episodes/") || !seenEp.add(href)) return@forEach
            val (s, e) = slugNumbers(href)
            val epNum = e ?: a.attr("data-ep-num").toIntOrNull()
            episodes += newEpisode(href) {
                this.name = epNum?.let { "الحلقة $it" }
                    ?: cleanTitle(a.attr("title").ifBlank { a.text() })
                this.season = s ?: hubSeason ?: 1
                this.episode = epNum
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        val seriesTitle = title
            .replace(Regex("""الحلقة\s+.*$"""), "")
            .trim()
            .ifBlank { title }
        return if (episodes.size >= 2 || (episodes.size == 1 && url.contains("/watch/tvshows/"))) {
            newTvSeriesLoadResponse(
                seriesTitle, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.TvSeries,
                episodes.ifEmpty {
                    val (s, e) = slugNumbers(url)
                    listOf(newEpisode(url) {
                        this.name = e?.let { "الحلقة $it" } ?: title
                        this.season = s ?: 1
                        this.episode = e
                    })
                }
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else if (episodes.size == 1) {
            newTvSeriesLoadResponse(
                seriesTitle, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // A lone episode page with no sibling list: play it as a
            // single-episode series entry pointing at itself.
            val (s, e) = slugNumbers(url)
            newTvSeriesLoadResponse(
                seriesTitle, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.TvSeries,
                listOf(newEpisode(url) {
                    this.name = e?.let { "الحلقة $it" } ?: title
                    this.season = s ?: 1
                    this.episode = e
                })
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ---------- playback ----------

    /** Orders embed URLs: hosts the app's extractors know come first. */
    private fun orderEmbeds(candidates: Collection<String>): List<String> {
        val priorityHosts = listOf(
            "vidmoly", "mixdrop", "byse", "hgcloud", "hglink", "streamhg", "doodstream",
            "streamwish", "uqload", "voe", "filemoon", "streamtape", "vidhide", "earnvids",
            "ok.ru", "odnoklassniki"
        )
        val known = candidates.filter { c -> priorityHosts.any { c.contains(it) } }
        return known + (candidates - known.toSet())
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

    /** Phone-side dig: fetch an embed page and pull the direct file out. */
    private suspend fun scanEmbed(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = runCatching {
            app.get(embed, headers = browserHeaders, referer = referer).text
        }.getOrNull() ?: return false
        val direct = extractDirect(text) ?: return false
        if (direct.isBlank()) return false
        emitDirect(direct, embed, callback)
        return true
    }

    /** Server-side dig via the relay's generic resolver. */
    private suspend fun resolveViaRelay(
        embed: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val relayUrl = "https://egydead-stremio.onrender.com/relay/resolve?url=" +
            java.net.URLEncoder.encode(embed, "UTF-8") +
            "&ref=" + java.net.URLEncoder.encode(referer, "UTF-8")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val diag = mutableListOf<String>()
        // Warm session: the see page only renders its player with the
        // session cookie the detail page sets.
        runCatching { app.get(data, headers = browserHeaders) }
        val seeUrl = data.trimEnd('/') + "/see/"
        var seeDoc: org.jsoup.nodes.Document? = null
        var seeCode: Any = "err"
        for (attempt in 1..2) {
            val res = runCatching {
                app.get(seeUrl, headers = browserHeaders, referer = data)
            }.getOrNull()
            if (res != null) {
                seeCode = res.code
                val doc = res.document
                if (doc.select("li.server_nth").isNotEmpty() ||
                    doc.select("iframe#iframe_player").isNotEmpty()
                ) {
                    seeDoc = doc
                    break
                }
                if (seeDoc == null) seeDoc = doc
            }
            Thread.sleep(1200)
        }
        diag += "see=$seeCode"
        val doc = seeDoc
        if (doc == null) {
            diag += "servers=0"
            emitDebug(diag, callback)
            return true
        }

        // The watch page's server list + default player iframe.
        val embedPages = LinkedHashSet<String>()
        doc.select("iframe#iframe_player").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http")) embedPages += fixUrl(src)
        }
        var serverCount = 0
        doc.select("li.server_nth").forEach { li ->
            serverCount++
            val post = li.attr("data-post").trim()
            val nume = li.attr("data-nume").trim()
            val type = li.attr("data-type").trim()
            if (post.isNotBlank() && nume.isNotBlank()) {
                val code = if (type == "tv") "2" else "1"
                embedPages += "$mainUrl/embed/$nume/$post/$code/"
            }
        }
        diag += "servers=$serverCount"

        // Each embed page nests the real host embed.
        val hostEmbeds = LinkedHashSet<String>()
        for (embedPage in embedPages) {
            val nested = runCatching {
                app.get(embedPage, headers = browserHeaders, referer = seeUrl).document
            }.getOrNull() ?: continue
            nested.select("iframe[src]").forEach {
                val src = it.attr("src").trim()
                if (src.startsWith("http") && !src.contains(mainUrl)) hostEmbeds += fixUrl(src)
            }
        }
        diag += "embeds=${hostEmbeds.size}"

        val ordered = orderEmbeds(hostEmbeds)
        var found = false
        val unresolved = mutableListOf<String>()
        for (embed in ordered) {
            if (loadExtractor(embed, data, subtitleCallback, callback)) {
                found = true
            } else {
                unresolved += embed
            }
        }
        if (!found) {
            var scanned = 0
            for (embed in unresolved) {
                if (scanEmbed(embed, data, callback)) {
                    scanned++
                    found = true
                }
            }
            diag += "scan=$scanned"
        }
        if (!found) {
            var relayed = 0
            for (embed in unresolved.take(3)) {
                relayed += resolveViaRelay(embed, data, callback)
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
