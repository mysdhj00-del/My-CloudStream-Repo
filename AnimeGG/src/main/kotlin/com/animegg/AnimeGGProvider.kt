package com.animegg

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * AnimeGG v3 — Desi's proven chain (Faisal0786/Desi AnimeGG v3),
 * reimplemented in clean Kotlin, + our subtitle gear + trailer v3.
 *
 * v3 fixes (user reports 2026-11-14 18:04 + 18:06, on-device v2):
 * (1) DUAL-ALWAYS: v2 emitted the combined "العربية +
 * الإنجليزية" entry only when OpenSubtitles had BOTH files. On
 * episodes with ready Arabic but NO English (Naruto Shippuden
 * ep 50 / ep 300, verified live: ara 3/1, eng 0), v2 emitted a
 * lone "العربية" and no dual at all. v3 translates the Arabic
 * file to English (gtx ar->en first, MyMemory ar|en fallback —
 * verified live) and always builds the combined entry; in dual
 * mode the combined entry is the ONLY subtitle emitted, singles
 * appear only when no combined file could be built. A ready
 * Arabic file is also stored as .vtt through the relay before
 * emission: the raw OS download URL has no file extension, which
 * leaves the player unable to detect the format (greyed entry).
 * (2) LOAD RESILIENCE: v2's load() returned a bare null (=
 * "Error loading") whenever the AniList GraphQL query failed —
 * AniList is HTTP 403 from datacenter IPs and may fail on the
 * phone too — and whenever the first direct page fetch threw.
 * v3 falls back: AniList failure -> AniList web-page title ->
 * site search; direct fetch failure -> alternate host retry ->
 * slug resolution through the site; episode parse falls back
 * from the resolved doc to the direct doc.
 *
 * Desi's chain, recovered from its compiled .cs3 (see
 * ~/workspace/animegg-study/NOTES.md, "Desi v3 chain (from dex)"):
 *   - Base URL is DYNAMIC: GET
 *     https://raw.githubusercontent.com/Faisal0786/test/main/urls.json
 *     -> key "animegg" = "https://animegg.org" (bare domain).
 *   - Home / search / detail metadata come from ANILIST GRAPHQL
 *     (POST https://graphql.anilist.co), NOT from animegg.org HTML:
 *     home lanes are AniList sorts, search is AniList SEARCH_MATCH
 *     with an animegg.org scrape as fallback, load() resolves an
 *     AniList id (or a title search) to AniList metadata, then
 *     findAnimeggDocument() finds the animegg series page by
 *     direct slug -> site search links -> fetched docs.
 *   - Episodes: li div:has(a.anm_det_pop), number from i.anititle,
 *     list reversed, ONE list (no sub/dub split).
 *   - Playback: episode page -> direct video/source tags, then
 *     iframes (iframe.video / iframe[src] / iframe[data-src]):
 *     if the iframe src IS a media URL, emit it; else fetch the
 *     embed page, take video/source tags + every mp4/m3u8/mpd URL
 *     in the page text (PLAYER_MEDIA_REGEX), and fall back to
 *     loadExtractor on the iframe URL. Labels come from the iframe
 *     context ("dubbed" -> مدبلج, else مترجم).
 *
 * The site serves NO subtitle tracks at all, so every subtitle
 * comes from the gear: OpenSubtitles (IMDb suggest, no key) for
 * ready AR/EN, else phone-side AI translation (free gtx first,
 * MyMemory fallback — gtx is carrier-blocked on the user's
 * phone, Panel #10, so the fallback is essential), both
 * directions (EN->AR and AR->EN).
 * Trailers follow the v3 rule: Latin titles only, Arabic-only
 * titles get the poster only, no YouTube query at all.
 */
class AnimeGGProvider : MainAPI() {

    override var mainUrl = "https://animegg.org"
    override var name = "AnimeGG"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "ar"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "latest" to "أحدث الحلقات",
        "popular" to "الأكثر رواجًا",
        "new" to "أُضيف حديثًا",
        "ongoing" to "مستمر",
        "completed" to "مكتمل"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // ---------- dynamic base URL (Desi) ----------

    @Volatile
    private var cachedBase: String? = null

    /** Desi's remote config; falls back to the bare domain. */
    private suspend fun baseUrl(): String {
        cachedBase?.let { return it }
        val fetched = try {
            val text = app.get(
                "https://raw.githubusercontent.com/Faisal0786/test/main/urls.json"
            ).text
            JSONObject(text).optString("animegg").trim()
                .takeIf { it.startsWith("https://") }?.trimEnd('/')
        } catch (_: Exception) {
            null
        }
        val base = fetched ?: "https://animegg.org"
        cachedBase = base
        return base
    }

    private fun fixThumb(u: String?): String? {
        if (u.isNullOrBlank()) return null
        var t = u.trim()
        if (t.startsWith("//")) t = "https:$t"
        return t
    }

    private fun absUrl(href: String?, base: String): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim()
        if (h.startsWith("http")) return h
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("/")) return base + h
        return null
    }

    private data class EpisodeRef(
        val u: String,
        val ep: Int,
        val t: String?,
        val l: String
    ) {
        fun toJson(): String {
            val o = JSONObject()
            o.put("u", u)
            o.put("ep", ep)
            if (t != null) o.put("t", t)
            o.put("l", l)
            return o.toString()
        }

        companion object {
            fun fromJson(raw: String): EpisodeRef? {
                return try {
                    val o = JSONObject(raw)
                    EpisodeRef(
                        o.optString("u"),
                        o.optInt("ep", 0),
                        o.optString("t").ifEmpty { null },
                        o.optString("l", "sub")
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    // ---------- AniList GraphQL (Desi) ----------

    private fun escapeGql(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** POST https://graphql.anilist.co, returns the parsed root or null. */
    private suspend fun queryAniList(query: String): JSONObject? {
        return try {
            val body = JSONObject().put("query", query).toString()
            val res = app.post(
                "https://graphql.anilist.co",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                ),
                requestBody = body.toRequestBody("application/json".toMediaType())
            )
            if (!res.isSuccessful) return null
            JSONObject(res.text)
        } catch (_: Exception) {
            null
        }
    }

    /** Titles for slug building: english, romaji, then synonyms. */
    private fun anilistTitles(media: JSONObject): List<String> {
        val out = ArrayList<String>()
        val t = media.optJSONObject("title")
        t?.optString("english")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        t?.optString("romaji")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        val syn = media.optJSONArray("synonyms")
        if (syn != null) {
            for (i in 0 until syn.length()) {
                syn.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    /** Page.media[] -> search cards pointing at anilist.co/anime/<id>. */
    private fun anilistCards(media: org.json.JSONArray): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val id = m.optInt("id", 0)
            if (id == 0) continue
            val title = m.optJSONObject("title")
            val name = title?.optString("english")?.takeIf { it.isNotBlank() }
                ?: title?.optString("romaji")?.takeIf { it.isNotBlank() }
                ?: continue
            val poster = m.optJSONObject("coverImage")
                ?.optString("extraLarge")?.takeIf { it.isNotBlank() }
                ?: m.optJSONObject("coverImage")?.optString("large")
            out.add(newAnimeSearchResponse(name, "https://anilist.co/anime/$id") {
                this.posterUrl = fixThumb(poster)
            })
        }
        return out
    }

    private suspend fun anilistLane(filter: String, page: Int): List<SearchResponse> {
        val q = "query { Page(page: $page, perPage: 20) { " +
            "media(type: ANIME, isAdult: false, $filter) { " +
            "id title { english romaji } coverImage { extraLarge large } } } }"
        val root = queryAniList(q) ?: return emptyList()
        val media = root.optJSONObject("data")?.optJSONObject("page")
            ?.optJSONArray("media") ?: return emptyList()
        return anilistCards(media)
    }

    // ---------- home ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val filter = when (request.data) {
            "latest" -> "status: RELEASING, sort: [UPDATED_AT_DESC]"
            "popular" -> "sort: [POPULARITY_DESC]"
            "new" -> "status: RELEASING, sort: [START_DATE_DESC]"
            "ongoing" -> "status: RELEASING, sort: [POPULARITY_DESC]"
            "completed" -> "status: FINISHED, sort: [POPULARITY_DESC]"
            else -> return null
        }
        val items = anilistLane(filter, page)
        return newHomePageResponse(request.name, items, items.size >= 20)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // AniList first (Desi).
        val gql = "query { Page(page: 1, perPage: 20) { " +
            "media(type: ANIME, isAdult: false, search: \"${escapeGql(query)}\", " +
            "sort: [SEARCH_MATCH]) { id title { english romaji } " +
            "coverImage { extraLarge large } } } }"
        val root = queryAniList(gql)
        val media = root?.optJSONObject("data")?.optJSONObject("page")
            ?.optJSONArray("media")
        if (media != null && media.length() > 0) return anilistCards(media)

        // Fallback: animegg.org search (Desi).
        val base = baseUrl()
        return try {
            val doc = app.get(
                "$base/search/?q=${URLEncoder.encode(query, "UTF-8")}",
                headers = browserHeaders,
                referer = base
            ).document
            val out = ArrayList<SearchResponse>()
            val seen = HashSet<String>()
            for (a in doc.select("a[href^=/series/]")) {
                val href = absUrl(a.attr("href"), base) ?: continue
                if (!seen.add(href)) continue
                val title = a.selectFirst("strong, h2, h3, .mse-title")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: a.text().trim().takeIf { it.isNotBlank() }
                    ?: continue
                val poster = a.selectFirst("img[src]")?.attr("src")
                    ?.let { fixThumb(it) }
                out.add(newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                })
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- detail ----------

    /** Desi's slug builder: season words normalized, then slugified. */
    private fun buildCandidateSlugs(titles: List<String>): List<String> {
        val out = ArrayList<String>()
        for (raw in titles) {
            var t = raw
            t = t.replace(Regex("(?i)\\b2nd\\s+season\\b"), "season 2")
            t = t.replace(Regex("(?i)\\b3rd\\s+season\\b"), "season 3")
            t = t.replace(Regex("(?i)\\b4th\\s+season\\b"), "season 4")
            t = t.replace(Regex("(?i)\\s+II$"), " season 2")
            t = t.replace(Regex("(?i)\\s+III$"), " season 3")
            val slug = t.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            if (slug.isNotBlank()) out.add(slug)
            if (raw.isNotBlank()) out.add(raw)
        }
        return out.distinct()
    }

    private fun extractFallbackTitle(doc: Document, url: String): String {
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val fromH1 = h1.replace(Regex("(?i)Episode.*"), "").trim()
        if (fromH1.isNotBlank()) return fromH1
        val og = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")?.trim().orEmpty()
        val fromOg = og.replace(Regex("(?i)Episode.*"), "").trim()
        if (fromOg.isNotBlank()) return fromOg
        return slugToTitle(url)
    }

    /** "naruto-shippuden" -> "Naruto Shippuden" (last resort title). */
    private fun slugToTitle(url: String): String {
        val slug = url.substringAfterLast("/").substringBefore("?")
            .substringBefore("#")
        val pretty = slug.replace("-", " ").split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercaseChar() }
        }
        return pretty.ifBlank { "Unknown Anime" }
    }

    /** Fetches a page document; null on any failure (never throws). */
    private suspend fun fetchDoc(url: String): Document? {
        return try {
            app.get(url, headers = browserHeaders, referer = baseUrl()).document
        } catch (_: Exception) {
            null
        }
    }

    /** animegg.org <-> www.animegg.org host swap (retry variant). */
    private fun altHost(url: String): String? {
        return when {
            url.startsWith("https://www.animegg.org/") ->
                url.replace("https://www.animegg.org/", "https://animegg.org/")
            url.startsWith("https://animegg.org/") ->
                url.replace("https://animegg.org/", "https://www.animegg.org/")
            else -> null
        }
    }

    /** Fetches a series doc only when it actually carries episodes. */
    private suspend fun fetchDocWithEpisodes(url: String): Document? {
        val doc = fetchDoc(url) ?: return null
        return doc.takeIf { it.select("li div:has(a.anm_det_pop)").isNotEmpty() }
    }

    /** AniList web page title (fallback when the GraphQL API is
     * blocked — HTTP 403 from datacenter IPs, verified live). */
    private suspend fun fetchAnilistWebTitle(url: String): String? {
        return try {
            val doc = app.get(url, headers = browserHeaders).document
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("title")?.text()?.trim()
                    ?.substringBefore("|")?.substringBefore("·")
                    ?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Parses the episode list out of a series page document.
     * Number comes from the episode URL FIRST (authoritative:
     * /<slug>-episode-<N>); the <i class="anititle"> text is
     * only a fallback, because it is often the episode TITLE
     * ("...Part 6: ...") whose first digits are NOT the episode
     * number (v2 bug: ep 499 parsed as 6, breaking the subtitle
     * lookup for that episode). */
    private fun parseEpisodes(
        doc: Document,
        base: String,
        display: String
    ): ArrayList<Episode> {
        val episodes = ArrayList<Episode>()
        for (li in doc.select("li div:has(a.anm_det_pop)")) {
            val a = li.selectFirst("a.anm_det_pop") ?: continue
            val epUrl = absUrl(a.attr("href"), base) ?: continue
            val num = Regex("""-episode-(\d+)""").find(epUrl)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""")
                    .find(a.selectFirst("i.anititle")?.text() ?: a.text())
                    ?.value?.toIntOrNull()
                ?: Regex("""\d+""").find(li.text())?.value?.toIntOrNull()
                ?: continue
            episodes.add(
                newEpisode(EpisodeRef(epUrl, num, display, "sub").toJson()) {
                    this.name = "الحلقة $num"
                    this.episode = num
                }
            )
        }
        episodes.reverse()
        return episodes
    }

    /** Desi's resolver: direct slugs, then site-search links, then fetched docs. */
    private suspend fun findAnimeggDocument(titles: List<String>): Document? {
        val base = baseUrl()
        val slugs = buildCandidateSlugs(titles)
        for (slug in slugs.take(5)) {
            // Bare host first, www host as the retry (v3).
            fetchDocWithEpisodes("$base/series/$slug")?.let { return it }
            val www = altHost("$base/series/$slug")
            if (www != null) fetchDocWithEpisodes(www)?.let { return it }
        }
        val links = ArrayList<String>()
        for (slug in slugs.take(3)) {
            try {
                val doc = app.get(
                    "$base/search/?q=${URLEncoder.encode(slug, "UTF-8")}",
                    headers = browserHeaders,
                    referer = base
                ).document
                for (a in doc.select("a[href^=/series/]")) {
                    absUrl(a.attr("href"), base)?.let { links.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        for (link in links.distinct().take(5)) {
            fetchDocWithEpisodes(link)?.let { return it }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = baseUrl()
        var meta: JSONObject? = null
        var directDoc: Document? = null
        var foundDoc: Document? = null
        val titles: List<String>
        if (url.startsWith("https://anilist.co/anime/")) {
            val id = url.substringAfterLast("/").substringBefore("?")
                .toIntOrNull() ?: return null
            val q = "query { Media(id: $id, type: ANIME) { " +
                "id title { english romaji } synonyms " +
                "description(asHtml: false) seasonYear averageScore genres " +
                "coverImage { extraLarge large } } }"
            meta = queryAniList(q)?.optJSONObject("data")?.optJSONObject("media")
            titles = if (meta != null) {
                anilistTitles(meta)
            } else {
                // v3: GraphQL blocked on this network — fall back to
                // the AniList web page title, then the site search.
                // Never a bare null while a title can still be found.
                val webTitle = fetchAnilistWebTitle(url) ?: return null
                listOf(webTitle)
            }
        } else {
            // v3: retry the direct fetch on the alternate host
            // before giving up on the page itself.
            directDoc = fetchDoc(url) ?: altHost(url)?.let { fetchDoc(it) }
            if (directDoc == null) {
                // Page unreachable directly: resolve via the slug
                // through the site search instead of failing.
                val slugTitle = slugToTitle(url)
                foundDoc = findAnimeggDocument(listOf(slugTitle))
                    ?: return null
                titles = listOf(slugTitle)
            } else {
                val fb = extractFallbackTitle(directDoc, url)
                val q = "query { Media(search: \"${escapeGql(fb)}\", " +
                    "type: ANIME, isAdult: false, sort: [SEARCH_MATCH]) { " +
                    "id title { english romaji } synonyms " +
                    "description(asHtml: false) seasonYear averageScore genres " +
                    "coverImage { extraLarge large } } }"
                meta = queryAniList(q)?.optJSONObject("data")?.optJSONObject("media")
                titles = if (meta != null) anilistTitles(meta) else listOf(fb)
            }
        }

        val doc = foundDoc ?: findAnimeggDocument(titles) ?: directDoc ?: return null

        val titleObj = meta?.optJSONObject("title")
        val display = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("romaji")?.takeIf { it.isNotBlank() }
            ?: extractFallbackTitle(doc, url)
        if (display.isBlank()) return null
        val poster = meta?.optJSONObject("coverImage")
            ?.optString("extraLarge")?.takeIf { it.isNotBlank() }
            ?: meta?.optJSONObject("coverImage")?.optString("large")
            ?: doc.selectFirst(".media-body img, .img img")?.attr("src")
                ?.let { fixThumb(it) }
        val plot = meta?.optString("description")?.takeIf { it.isNotBlank() }
            ?.replace(Regex("<[^>]*>"), "")?.trim()
            ?: doc.selectFirst(".pdesc, .description, p")?.text()?.trim()
        val year = meta?.optInt("seasonYear", 0)?.takeIf { it > 0 }
        val score = meta?.optDouble("averageScore", 0.0)?.takeIf { it > 0 }
            ?.let { Score.from100(it.toInt()) }
        val tags = meta?.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.optString(it).takeIf { s -> s.isNotBlank() }
            }
        }

        // v3: if the resolved doc yields no episodes, try the
        // direct doc before reporting an empty series.
        var episodes = parseEpisodes(doc, base, display)
        if (episodes.isEmpty() && directDoc != null && directDoc !== doc) {
            episodes = parseEpisodes(directDoc, base, display)
        }
        if (episodes.isEmpty()) return null

        val type = when {
            display.contains("(OVA)", ignoreCase = true) -> TvType.OVA
            episodes.size <= 1 -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        return newAnimeLoadResponse(display, url, type) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
            this.trailers = AnimeGGSubs.trailerFor(
                display,
                isSeries = type != TvType.AnimeMovie,
                acceptPv = true
            )
        }
    }

    // ---------- playback (Desi) ----------

    private val mediaRe = Regex("""(?i)\.(?:mp4|m3u8|mpd)(?:$|[?#])""")

    private val playerMediaRe = Regex(
        """(?i)(?:file|src|source)\s*['"]?\s*[:=]\s*['"]([^'"]+?\.(?:mp4|m3u8|mpd)(?:[?#][^'"]*)?)['"]"""
    )

    private val videoSourcesRe =
        Regex("""\{file:\s*"([^"]+)",\s*label:\s*"([^"]+)",\s*bk:\s*"([^"]*)"""")

    private fun decodeBk(bk: String): String? {
        return try {
            if (bk.isBlank()) return null
            val raw = android.util.Base64.decode(bk, android.util.Base64.DEFAULT)
            URLDecoder.decode(String(raw, Charsets.UTF_8), "UTF-8")
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun qualityOf(label: String): Int {
        return when (
            Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        ) {
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** Desi's resolver: unescape, scheme-fix, else resolve against the referer. */
    private fun resolveMediaUrl(url: String, referer: String?): String? {
        return try {
            var u = url.trim().replace("&amp;", "&").replace("\\/", "/")
            u = when {
                u.startsWith("https://") || u.startsWith("http://") -> u
                u.startsWith("//") -> "https:$u"
                else -> {
                    val base = referer ?: return null
                    java.net.URI(base).resolve(u).toString()
                }
            }
            u
        } catch (_: Exception) {
            null
        }
    }

    private data class SubResult(val os: String, val tr: String)

    /** Fetches a subtitle file's text; null on any failure. */
    private suspend fun fetchTextOrNull(url: String): String? {
        return try {
            val res = app.get(url)
            if (res.isSuccessful) res.text.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Subtitle gear v3 (dual-always, user order 2026-11-14):
     * OpenSubtitles by title (IMDb suggest, no key). In DUAL mode
     * the combined "العربية + الإنجليزية" entry is ALWAYS built
     * when either side exists — AR-only episodes are translated
     * AR->EN (gtx ar->en first, MyMemory ar|en fallback) and
     * EN-only episodes EN->AR (existing path) — and the combined
     * entry is then the ONLY subtitle emitted. Singles are
     * emitted only when no combined file could be built at all.
     * A ready Arabic file is stored as .vtt through the relay
     * before emission: the raw OS download URL has no file
     * extension, which leaves the player unable to detect the
     * format (greyed entry on-device). English-only mode never
     * translates and is unchanged.
     */
    private suspend fun runOsFlow(
        ref: EpisodeRef,
        subtitleCallback: (SubtitleFile) -> Unit
    ): SubResult {
        val mode = AnimeGGGear.mode
        val title = ref.t ?: return SubResult("no-title", "-")
        val tt = AnimeGGSubs.suggestImdbId(title, true) ?: return SubResult("no-tt", "-")
        val osUrl = "https://opensubtitles-v3.strem.io/subtitles/series/$tt:1:${ref.ep}.json"
        val arr = try {
            val res = app.get(osUrl)
            if (!res.isSuccessful) return SubResult("no-os", "-")
            JSONObject(res.text).optJSONArray("subtitles")
        } catch (_: Exception) {
            null
        } ?: return SubResult("no-os", "-")
        val ara = AnimeGGSubs.bestOsSub(arr, "ara")
        val eng = AnimeGGSubs.bestOsSub(arr, "eng")

        if (mode == "en") {
            if (eng != null) {
                subtitleCallback(SubtitleFile("English", eng))
                return SubResult("en-only", "-")
            }
            return SubResult("no-match", "-")
        }

        fun relayMergeUrl(engUrl: String, araUrl: String): String =
            "${AnimeGGSubs.relayBase}/relay/mergesubs?eng=" +
                URLEncoder.encode(engUrl, "UTF-8") +
                "&ara=" + URLEncoder.encode(araUrl, "UTF-8")

        // Stores a ready Arabic file as .vtt through the relay so
        // the player can detect the format; null when the fetch or
        // the store fails (caller falls back to the raw URL).
        suspend fun storedArUrl(araUrl: String): String? {
            val raw = fetchTextOrNull(araUrl) ?: return null
            val cues = AnimeGGSubs.parseSubCues(raw)
            if (cues.isEmpty()) return null
            return AnimeGGSubs.storeSub(AnimeGGSubs.buildSubVTT(cues))
        }

        if (mode == "ar") {
            if (ara != null) {
                subtitleCallback(SubtitleFile("العربية", storedArUrl(ara) ?: ara))
                return SubResult("ar", "-")
            }
            if (eng != null) {
                val raw = fetchTextOrNull(eng)
                val cues = raw?.let { AnimeGGSubs.parseSubCues(it) }.orEmpty()
                if (cues.isEmpty()) return SubResult("no-cues", "-")
                val res = AnimeGGTranslate.translateCues(cues, eng, raw!!)
                if (res.arUrl != null) {
                    subtitleCallback(SubtitleFile("العربية", res.arUrl))
                    val trMark = if (AnimeGGTranslate.mmUsed) "tr-mm" else "tr-ok"
                    return SubResult("tr", trMark)
                }
                return SubResult("tr-fail", res.failStage ?: "gtx")
            }
            return SubResult("no-match", "-")
        }

        // mode == "dual" from here: combined entry first, always.
        if (ara != null && eng != null) {
            subtitleCallback(
                SubtitleFile("العربية + الإنجليزية", relayMergeUrl(eng, ara))
            )
            return SubResult("ar+dual", "-")
        }

        if (ara != null) {
            // AR-only (e.g. Naruto Shippuden ep 50 / ep 300: ara
            // present, eng 0 on OpenSubtitles): translate the
            // Arabic file to English, then merge AR+EN.
            val raw = fetchTextOrNull(ara)
            val cues = raw?.let { AnimeGGSubs.parseSubCues(it) }.orEmpty()
            if (cues.isNotEmpty()) {
                val res = AnimeGGTranslate.translateCuesArToEn(cues, ara)
                if (res.enUrl != null) {
                    val dual = res.dualUrl ?: relayMergeUrl(res.enUrl, ara)
                    subtitleCallback(SubtitleFile("العربية + الإنجليزية", dual))
                    val trMark = if (AnimeGGTranslate.mmUsed) "tr-mm-ar-en" else "tr-ar-en"
                    return SubResult("ar+tr-en", trMark)
                }
                // Translation failed: single Arabic (stored .vtt
                // when possible) as the only fallback.
                val stored = AnimeGGSubs.storeSub(AnimeGGSubs.buildSubVTT(cues))
                subtitleCallback(SubtitleFile("العربية", stored ?: ara))
                return SubResult("ar", "-")
            }
            subtitleCallback(SubtitleFile("العربية", ara))
            return SubResult("ar", "-")
        }

        if (eng != null) {
            val raw = fetchTextOrNull(eng)
            val cues = raw?.let { AnimeGGSubs.parseSubCues(it) }.orEmpty()
            if (cues.isEmpty()) {
                // No usable English text: single English as the
                // only fallback.
                subtitleCallback(SubtitleFile("English", eng))
                return SubResult("no-cues", "-")
            }
            val res = AnimeGGTranslate.translateCues(cues, eng, raw!!)
            if (res.arUrl != null) {
                val dual = res.dualUrl ?: relayMergeUrl(eng, res.arUrl)
                subtitleCallback(SubtitleFile("العربية + الإنجليزية", dual))
                val trMark = if (AnimeGGTranslate.mmUsed) "tr-mm" else "tr-ok"
                return SubResult("tr", trMark)
            }
            // Translation failed: single English as the only fallback.
            subtitleCallback(SubtitleFile("English", eng))
            return SubResult("tr-fail", res.failStage ?: "gtx")
        }
        return SubResult("no-match", "-")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val ref = EpisodeRef.fromJson(data)
            ?: EpisodeRef(
                data,
                Regex("""-episode-(\d+)""").find(data)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                null,
                "sub"
            )
        if (ref.u.isBlank()) return false
        val mode = AnimeGGGear.mode
        val base = baseUrl()

        val epDoc = try {
            app.get(ref.u, headers = browserHeaders, referer = base).document
        } catch (e: Exception) {
            return false
        }
        if (epDoc.select("img[src*=animegg-unavailable]").isNotEmpty()) return false

        val emitted = HashSet<String>()
        var videoLinks = 0
        var bkLinks = 0
        var extLinks = 0
        var regexLinks = 0
        var iframeCount = 0

        suspend fun emitDirect(name: String, rawUrl: String, referer: String): Boolean {
            val url = resolveMediaUrl(rawUrl, referer) ?: return false
            if (!mediaRe.containsMatchIn(url)) return false
            if (!emitted.add(url)) return false
            videoLinks++
            callback(
                newExtractorLink(
                    "AnimeGG",
                    name,
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityOf(url)
                    this.referer = referer
                    this.headers = browserHeaders
                }
            )
            return true
        }

        // Step 1: direct <video>/<source> tags on the episode page (Desi).
        for (el in epDoc.select("video[src], video source[src], source[src]")) {
            emitDirect("AnimeGG", el.attr("src"), ref.u)
        }

        // Step 2: iframes (Desi).
        val frames = epDoc.select("iframe.video, iframe[src], iframe[data-src]")
        iframeCount = frames.size
        for (frame in frames) {
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
                .ifBlank { frame.attr("data-lazy-src") }
            if (src.isBlank() || src.startsWith("javascript:") ||
                src.startsWith("about:")
            ) continue
            val frameUrl = resolveMediaUrl(src, ref.u) ?: continue
            val ctx = (frame.className() + " " +
                (frame.parent()?.className().orEmpty()) + " " +
                (frame.parent()?.id().orEmpty())).lowercase(Locale.ROOT)
            val label = if (ctx.contains("dubbed")) "AnimeGG مدبلج" else "AnimeGG مترجم"
            if (emitDirect(label, frameUrl, ref.u)) continue

            // Fetch the embed page.
            val embText = try {
                val res = app.get(frameUrl, headers = browserHeaders, referer = ref.u)
                if (!res.isSuccessful) "" else res.text
            } catch (_: Exception) {
                ""
            }
            if (embText.isBlank()) continue
            var found = false
            val embDoc = try {
                Jsoup.parse(embText)
            } catch (_: Exception) {
                null
            }
            if (embDoc != null) {
                for (el in embDoc.select("video[src], video source[src], source[src]")) {
                    if (emitDirect(label, el.attr("src"), frameUrl)) found = true
                }
            }
            for (m in playerMediaRe.findAll(embText)) {
                if (emitDirect(label, m.groupValues[1], frameUrl)) {
                    found = true
                    regexLinks++
                }
            }
            // v1 bonus: base64 backup CDN URLs from JWPlayer videoSources.
            for (m in videoSourcesRe.findAll(embText)) {
                val bk = decodeBk(m.groupValues[3])
                if (bk != null && emitted.add(bk)) {
                    bkLinks++
                    callback(
                        newExtractorLink(
                            "AnimeGG",
                            "$label (احتياطي)",
                            bk,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityOf(m.groupValues[2])
                            this.referer = frameUrl
                            this.headers = browserHeaders
                        }
                    )
                }
            }
            if (!found) {
                // Stock-extractor fallback on the iframe URL (Desi).
                try {
                    loadExtractor(frameUrl, ref.u, subtitleCallback) { link ->
                        extLinks++
                        callback(link)
                    }
                } catch (_: Exception) {
                }
            }
        }

        // ---- subtitle gear (mode: ar | en | dual, default dual) ----
        // Wrapped so a subtitle failure can never kill the video links.
        AnimeGGTranslate.gtx429 = false
        val subRes = if (videoLinks > 0 || bkLinks > 0 || extLinks > 0) {
            try {
                runOsFlow(ref, subtitleCallback)
            } catch (_: Exception) {
                SubResult("err", "-")
            }
        } else SubResult("-", "-")

        val secs = (System.currentTimeMillis() - t0) / 1000
        val debugName = "DEBUG AnimeGG v3 | mode=$mode | fr=$iframeCount" +
            " | vid=$videoLinks | bk=$bkLinks | ext=$extLinks | rx=$regexLinks" +
            " | sub:os=${subRes.os} | sub:tr=${subRes.tr}" +
            (if (AnimeGGTranslate.gtx429) " | sub:gtx429" else "") +
            (if (AnimeGGTranslate.mmLimit) " | sub:mm-limit" else "") +
            " | t=${secs}s"
        callback(
            newExtractorLink(
                "Mux",
                debugName,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
            }
        )
        return videoLinks > 0 || bkLinks > 0 || extLinks > 0
    }
}
