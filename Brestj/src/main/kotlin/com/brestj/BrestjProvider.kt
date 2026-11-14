package com.brestj

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Brestj (prstej.lat — the site moved from brstej.com, new template
 * "prestige-theme-v14") provider for CloudStream 3.
 *
 * Built against the live structure verified on 2026-10-15, re-verified
 * 2026-11-12 after the move. Brestj runs the same PHP template family
 * as MyCima (video.php?vid={id} for movies AND episodes; an episode
 * page lists its series' sibling episodes as titled anchors; listings
 * are cat03.php?cat={slug} and new-videos.php, paginating with
 * &page={n}; search is search.php?keywords={q}). Cards are SPLIT
 * across several anchors sharing one video.php URL — a photo anchor
 * (img, duration text, aria-label), a title anchor (clean text, no
 * img) and a detail anchor ("للمشاهدة ←") — so parseCards groups
 * anchors by URL and merges poster + title per card.
 * - Playback (traced before writing this): play.php?vid={id} lists the
 *   servers as direct embeds (hdup400, film77, hd-vk, ok.ru, ...). The
 *   app's extractors know only some of them, so loadLinks reuses the
 *   MyCima v2 three-stage chain: built-in extractors, then a phone-side
 *   dig for the direct file inside each rejected embed page (JW file: /
 *   ArtPlayer videoUrl / bare media URLs), then the relay server's
 *   generic /relay/resolve dig.
 * - Season quirk of this site: titles carry the season as a bare number
 *   right before "الحلقة" (مسلسل الخليفة 2 الحلقة 39 = season 2), which
 *   seasonOfOrNull understands alongside the الموسم forms.
 * - v8: series category pages carry a site-wide "أهم الحلقات" featured
 *   widget above the real list — parseCards reads only #pm-grid when
 *   it exists; episode cards merge into ONE card per series (core
 *   name); load() tracks the season from the most recent "الموسم N"
 *   heading while walking the page in order.
 * - Every request carries an Android-Chrome header set.
 */
class BrestjProvider : MainAPI() {
    override var mainUrl = "https://prstej.lat"
    override var name = "Brestj"
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

    // Sections mirror the site's own category menu.
    override val mainPage = mainPageOf(
        "$mainUrl/new-videos.php" to "المضاف حديثا",
        "$mainUrl/cat03.php?cat=aflamajnby3-2024" to "أفلام أجنبية",
        "$mainUrl/cat03.php?cat=aflam02-2024" to "أفلام عربية",
        "$mainUrl/cat03.php?cat=turkish3-movies2024" to "أفلام تركية",
        "$mainUrl/cat03.php?cat=hindi1-moviess" to "أفلام هندية",
        "$mainUrl/cat03.php?cat=anime1" to "أفلام أنمي",
        "$mainUrl/cat03.php?cat=english1-2025" to "مسلسلات أجنبية",
        "$mainUrl/cat03.php?cat=arab8-2025" to "مسلسلات عربية",
        "$mainUrl/cat03.php?cat=ty9-2025" to "مسلسلات تركية",
        "$mainUrl/cat03.php?cat=asia" to "مسلسلات آسيوية",
        "$mainUrl/cat03.php?cat=2ind2-2025" to "مسلسلات هندية",
        "$mainUrl/cat03.php?cat=5a7-2024" to "مسلسلات خليجية",
        "$mainUrl/cat03.php?cat=anmei" to "مسلسلات أنمي",
        "$mainUrl/cat03.php?cat=ramdan2026" to "مسلسلات رمضان 2026",
        "$mainUrl/cat03.php?cat=rm42-2022" to "مسلسلات رمضان 2022",
        "$mainUrl/cat03.php?cat=movies2-2224" to "أحدث الأفلام",
        "$mainUrl/cat03.php?cat=syy5-2025" to "مسلسلات شامية",
        "$mainUrl/cat03.php?cat=eg8-2025" to "مسلسلات مصرية",
        "$mainUrl/cat03.php?cat=rmdan31-2021" to "مسلسلات رمضان 2021",
        "$mainUrl/cat03.php?cat=ramda1-2023" to "مسلسلات رمضان 2023",
        "$mainUrl/cat03.php?cat=ramdan1-2024" to "مسلسلات رمضان 2024",
        "$mainUrl/cat03.php?cat=ramadan2-2025" to "مسلسلات رمضان 2025",
        "$mainUrl/cat03.php?cat=wait" to "حلقات التجهيز",
    )

    // Per-section set of card URLs already emitted, so sideways scrolling
    // shows every page exactly once: a past-the-end page that clamps to
    // the last page adds zero NEW cards and the lane stops instead of
    // looping on repeats.
    private val seenBySection = HashMap<String, HashSet<String>>()

    // Per-section set of series core names already emitted (v8): a later
    // page may carry the same series under a different episode URL, so
    // the URL set alone cannot stop series repeats.
    private val seenSeriesBySection = HashMap<String, HashSet<String>>()

    // ---------- helpers ----------

    private fun cleanTitle(raw: String): String = raw
        .trim()
        .removePrefix("مشاهدة")
        .trim()
        .replace(Regex("""\s*\|.*$"""), "")
        .trim()

    private fun isAnimeTitle(title: String): Boolean =
        title.contains("انمي") || title.contains("أنمي") || title.contains("كرتون")

    /** "مشاهدة فيلم TITLE مترجم اون لاين HD" → "TITLE". */
    private fun cleanAriaLabel(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("مشاهدة فيلم").removePrefix("مشاهدة مسلسل")
            .removePrefix("مشاهدة انمي").removePrefix("مشاهدة").trim()
        s = s.removeSuffix("مترجم اون لاين HD").removeSuffix("مترجم اون لاين").trim()
        return cleanTitle(s)
    }

    /** Text that must never become a title: a duration badge ("1:30:37")
     * or the detail anchor ("للمشاهدة ←"). */
    private fun isJunkTitleText(text: String): Boolean {
        if (text.isBlank()) return true
        if (Regex("""^\d+:\d+(:\d+)?$""").matches(text)) return true
        if (text.contains("للمشاهدة")) return true
        return false
    }

    /**
     * Merges one card's anchors (all sharing one video.php URL) into a
     * single SearchResponse. The new template splits each card across a
     * photo anchor (img, duration text, aria-label), a title anchor
     * (clean text, no img) and a detail anchor ("للمشاهدة ←").
     */
    private fun List<Element>.toCard(href: String): SearchResponse? {
        // Poster: the <img> inside ANY same-URL anchor.
        var poster: String? = null
        for (a in this) {
            val img = a.selectFirst("img") ?: continue
            poster = fixUrlNull(img.attr("data-echo"))
                ?: fixUrlNull(img.attr("data-src"))
                ?: fixUrlNull(img.attr("src"))?.takeIf { !it.contains("blank") && !it.contains("pixel") }
            if (poster != null) break
        }
        // Title: prefer the clean text of a non-photo anchor; fall back to
        // the photo anchor's aria-label (or the img alt, which carries the
        // same label on cat pages).
        var title: String? = null
        for (a in this) {
            val text = a.text().trim().replace(Regex("""\s+"""), " ")
            if (isJunkTitleText(text)) continue
            title = cleanTitle(a.attr("title").ifBlank { text })
            if (!title.isNullOrBlank()) break
        }
        if (title.isNullOrBlank()) {
            for (a in this) {
                val label = a.attr("aria-label").trim()
                if (label.isNotBlank()) {
                    title = cleanAriaLabel(label)
                    if (!title.isNullOrBlank()) break
                }
                val alt = a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                if (alt.isNotBlank()) {
                    title = cleanAriaLabel(alt)
                    if (!title.isNullOrBlank()) break
                }
            }
        }
        if (title.isNullOrBlank()) return null
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        return buildCard(title, href, poster, year)
    }

    /** Builds the typed card for a title, shared by toCard and the
     * series-grouping step (which retitles a card to its series core). */
    private fun buildCard(title: String, href: String, poster: String?, year: Int?): SearchResponse {
        val isSeries = title.contains("مسلسل") || title.contains("الحلقة") || title.contains("الموسم")
        return when {
            isAnimeTitle(title) ->
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

    /**
     * The series a card belongs to, or null when the card is not a series
     * episode (no "الحلقة" in its title). The key keeps the "مسلسل"/"انمي"
     * prefix and a bare season number before "الحلقة" ("الخليفة 2"), and
     * drops the season part, the episode tail and the مترجمة/مدبلجة/HD
     * tails — so every episode of one series maps to the same key.
     */
    private fun seriesCoreKey(title: String): String? {
        if (!title.contains("الحلقة")) return null
        var s = title
        s = s.replace(Regex("""\s*الموسم\s.*$"""), "")
        s = s.replace(Regex("""\s*الحلقة\s.*$"""), "")
        s = s.replace(Regex("""\s+((مترجمة|مدبلجة|HD)\s*)+$"""), "")
        s = s.trim()
        return s.ifBlank { null }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        // Series category pages inject a site-wide "أهم الحلقات" featured
        // widget above the real list — when the real list (#pm-grid)
        // exists, read ONLY inside it.
        val scope: Element = selectFirst("#pm-grid") ?: this
        // Group every video.php anchor by its URL, then merge each group
        // into one card (poster + title may live on different anchors).
        val byUrl = LinkedHashMap<String, MutableList<Element>>()
        scope.select("a[href*=\"video.php?vid=\"]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            byUrl.getOrPut(href) { mutableListOf() } += a
        }
        val merged = ArrayList<SearchResponse>()
        for ((href, anchors) in byUrl) {
            val item = runCatching { anchors.toCard(href) }.getOrNull() ?: continue
            merged += item
        }
        // ONE card per series: episode cards of the same series collapse
        // into the first (topmost = latest) episode's card, retitled to
        // the series core name. Movies are never grouped.
        val out = ArrayList<SearchResponse>()
        val seenSeries = HashSet<String>()
        for (item in merged) {
            val key = seriesCoreKey(item.name)
            if (key == null) {
                out += item
                continue
            }
            if (!seenSeries.add(key)) continue
            // SearchResponse carries no year property — re-read it from
            // the core title (the year sits before الموسم/الحلقة, so it
            // survives in the key).
            val keyYear = Regex("""(19|20)\d{2}""").find(key)?.value?.toIntOrNull()
            out += buildCard(key, item.url, item.posterUrl, keyYear)
        }
        return out
    }

    private val seasonOrdinals = mapOf(
        "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
        "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
        "العاشر" to 10, "الحادي عشر" to 11, "الثاني عشر" to 12
    )

    private fun seasonOf(title: String): Int = seasonOfOrNull(title) ?: 1

    /** The season a title explicitly names, or null when it names none. */
    private fun seasonOfOrNull(title: String): Int? {
        Regex("""الموسم\s+(\d{1,2})""").find(title)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        for ((word, num) in seasonOrdinals) {
            if (title.contains("الموسم $word") || title.contains("موسم $word")) return num
        }
        // Brestj style: a bare season number directly before "الحلقة"
        // (مسلسل الخليفة 2 الحلقة 39 means season 2, episode 39).
        Regex("""(\d{1,2})\s+الحلقة""").find(title)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    // ---------- API ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else request.data + (if (request.data.contains("?")) "&page=$page" else "?page=$page")
        val document = app.get(url, headers = browserHeaders).document
        val items = document.parseCards()
        val seen = seenBySection.getOrPut(request.data) { HashSet() }
        val seenSeries = seenSeriesBySection.getOrPut(request.data) { HashSet() }
        if (page <= 1) {
            seen.clear()
            seenSeries.clear()
        }
        val fresh = items.filter { item ->
            if (!seen.add(item.url)) return@filter false
            // A series card counts as new only if its series is new to
            // this section — a later page may carry the same series
            // under a different episode URL.
            if ((item.type == TvType.TvSeries || item.type == TvType.Anime) &&
                !seenSeries.add(item.name)
            ) return@filter false
            true
        }
        return newHomePageResponse(request.name, fresh, hasNext = fresh.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl + "/search.php?keywords=" + java.net.URLEncoder.encode(query, "UTF-8")
        return app.get(url, headers = browserHeaders).document.parseCards()
    }

    // Trailer (rollout, pattern from HorrorMovies v11): a trailer
    // button on every detail page, from a free YouTube search.
    // Desktop UA is REQUIRED — the mobile search page returns no
    // parseable results. The setter wants a MutableList. Anime:
    // "PV" titles count as trailers too.
    private val trailerHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

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

    private suspend fun trailerFor(
        title: String,
        isSeries: Boolean = false,
        acceptPv: Boolean = false
    ): MutableList<TrailerData> {
        return try {
            val year = Regex("""(19|20)\d\d""").find(title)?.value
            val queries = ArrayList<String>()
            for (cand in latinTitleCandidates(title)) {
                queries += if (isSeries) "$cand official trailer"
                else listOfNotNull(cand, year, "official trailer").joinToString(" ")
            }
            if (queries.isEmpty()) {
                val clean = cleanTitle(title)
                if (clean.isNotBlank()) {
                    queries += if (isSeries) "$clean الإعلان الرسمي"
                    else listOfNotNull(clean, year, "trailer").joinToString(" ")
                }
            }
            for (q in queries) {
                val id = youtubeTrailerId(q, acceptPv) ?: continue
                return mutableListOf(
                    TrailerData(
                        "https://www.youtube.com/watch?v=$id",
                        "https://www.youtube.com/",
                        false
                    )
                )
            }
            mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private suspend fun youtubeTrailerId(query: String, acceptPv: Boolean = false): String? {
        return try {
            val url = "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val html = app.get(url, headers = trailerHeaders).text
            if (html.isBlank()) return null
            val idRe = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
            val titleRe =
                Regex(""""title":\{"runs":\[\{"text":"([^"]{3,120})"""")
            var firstId: String? = null
            val seen = HashSet<String>()
            for (m in idRe.findAll(html)) {
                val id = m.groupValues[1]
                if (!seen.add(id)) continue
                if (firstId == null) firstId = id
                val end = minOf(html.length, m.range.last + 800)
                val window = html.substring(m.range.last, end)
                val t = titleRe.find(window)?.groupValues?.get(1) ?: continue
                if (t.contains("trailer", ignoreCase = true)) return id
                if (acceptPv && t.contains("PV")) return id
            }
            firstId
        } catch (_: Exception) {
            null
        }
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

        // A video page lists its series' episodes two ways (both verified on
        // هند خانم: the ep46 page carries the complete ordered 1–58 list):
        // a numbered strip of anchors whose whole text is "N حلقة", and
        // titled anchors carrying "الحلقة N" plus the series' core name
        // (recommendations on the same page name other series, so the shared
        // name is the filter there). Multi-season pages group their strips
        // under "الموسم N" headings (verified on Game of Thrones, S1–S8),
        // so the strip season is tracked from the most recent heading.
        // Episode anchors name no season — the season lives in the page's
        // own title — so episodes inherit the page's season unless an
        // anchor overrides it. The core name must strip the page's own
        // episode tail first, or no sibling matches it.
        val hubSeason = seasonOf(title)
        val core = title
            .removePrefix("مسلسل").trim()
            .replace(Regex("""\s+الحلقة\s+.*$"""), "").trim()
            .replace(Regex("""الموسم\s+.*$"""), "").trim()
            .removePrefix("انمي").trim()
        val episodes = mutableListOf<Episode>()
        val seenEp = HashSet<String>()
        // (season, episode) pairs already collected — the numbered strip
        // and the titled anchors can name the same episode under
        // different URLs.
        val seenSeasonEp = HashSet<Pair<Int, Int>>()
        // Walk the page in document order, tracking the season from the
        // most recent "الموسم N" heading for the numbered strips.
        var trackedSeason: Int? = null
        document.select("h1, h2, h3, h4, a[href*=\"video.php?vid=\"]").forEach { el ->
            if (el.tagName().startsWith("h")) {
                seasonOfOrNull(el.text().trim())?.let { trackedSeason = it }
                return@forEach
            }
            val a = el
            val href = fixUrl(a.attr("href"))
            if (!seenEp.add(href)) return@forEach
            val anchorText = a.text().trim().replace(Regex("""\s+"""), " ")
            // Numbered episode strip: the anchor's whole text is "N حلقة".
            val numbered = Regex("""^(\d{1,4})\s*حلقة$""").find(anchorText)
            if (numbered != null) {
                val n = numbered.groupValues[1].toIntOrNull() ?: return@forEach
                val season = trackedSeason ?: hubSeason
                if (!seenSeasonEp.add(season to n)) return@forEach
                episodes += newEpisode(href) {
                    this.name = "الحلقة $n"
                    this.season = season
                    this.episode = n
                }
                return@forEach
            }
            val epTitle = cleanTitle(a.attr("title").ifBlank { a.text() })
            if (!epTitle.contains("الحلقة")) return@forEach
            if (core.isNotBlank() && !epTitle.contains(core, ignoreCase = true)) return@forEach
            val epNum = Regex("""الحلقة\s+(\d{1,4})""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            val season = seasonOfOrNull(epTitle) ?: hubSeason
            if (epNum != null && !seenSeasonEp.add(season to epNum)) return@forEach
            episodes += newEpisode(href) {
                this.name = epNum?.let { "الحلقة $it" } ?: epTitle
                this.season = season
                this.episode = epNum
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        return if (episodes.size >= 2) {
            newTvSeriesLoadResponse(
                title, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.trailers = trailerFor(title, isSeries = true, acceptPv = isAnimeTitle(title))
            }
        } else {
            newMovieLoadResponse(
                title, url,
                if (isAnimeTitle(title)) TvType.Anime else TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.trailers = trailerFor(title, isSeries = false, acceptPv = isAnimeTitle(title))
            }
        }
    }

    /**
     * Extractor-friendly hosts first; the site's own vipserver and other
     * unknown shells last (and skipped entirely when a known host exists).
     */
    private fun orderEmbeds(candidates: Collection<String>): List<String> {
        val priorityHosts = listOf(
            "vidmoly", "mixdrop", "byse", "hgcloud", "hglink", "streamhg", "doodstream",
            "streamwish", "uqload", "voe", "filemoon", "streamtape", "vidhide", "earnvids"
        )
        val known = candidates.filter { c -> priorityHosts.any { c.contains(it) } }
        return if (known.isNotEmpty()) known + (candidates - known.toSet())
            .filter { !it.contains("liiivideo") && !it.contains("minochinos") }
        else candidates.toList()
    }

    /**
     * Scans an embed page's text for a direct media URL (JW-style file:
     * entries, an ArtPlayer videoUrl constant, or a bare .m3u8/.mp4 URL).
     * Shared by the phone-side scan and mirrored by the relay's resolver.
     */
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
                // v5: the vhdup200 CDN answers 403 to EVERY request in the
                // chain (master, variants, segments) whose Referer is not
                // the hdup400 embed page itself (verified live 2026-10-17:
                // segment + referer=master -> 403, + referer=embed -> 200).
                // Carry the referer in the headers map too, not only the
                // referer field, so whichever channel the player propagates
                // to segment requests is covered.
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to browserHeaders.getValue("User-Agent")
                )
            }
        )
    }

    // Dean Edwards packer blocks: eval(function(p,a,c,k,e,d){...}('payload',
    // radix, count, 'k1|k2|...'.split('|'))). hdup400 (the only server on
    // most Brestj episodes) hides its JW config this way; unpacked, it
    // carries a plain file:"...master.m3u8" (verified live 2026-10-16).
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

    /**
     * Stage 2 (phone-side): fetch an embed page the built-in extractors
     * couldn't handle and pull the direct file out of it — scanning the raw
     * page first, then the unpacked form of any packed-JS player config.
     * Returns true when a link was emitted.
     */
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

    /**
     * Stage 3 (server-side): asks the relay's generic resolver to fetch the
     * embed page and extract the direct file. Returns the number of links
     * emitted (0 when the relay yields nothing).
     */
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Field diagnostics: stage markers are surfaced as one playable DEBUG
        // source when nothing playable is found.
        val diag = mutableListOf<String>()
        val vid = Regex("""vid=([a-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            diag += "vid:missing"
            emitDebug(diag, callback)
            return true
        }

        val playUrl = "$mainUrl/play.php?vid=$vid"
        // v5: one retry — a single flaked play.php response used to mean
        // zero candidates and a dead end for the whole episode.
        var res = runCatching { app.get(playUrl, headers = browserHeaders, referer = data) }.getOrNull()
        if (res == null || res.code != 200) {
            Thread.sleep(1200)
            res = runCatching { app.get(playUrl, headers = browserHeaders, referer = data) }.getOrNull() ?: res
        }
        diag += "play=${res?.code ?: "err"}"

        val candidates = LinkedHashSet<String>()
        if (res != null) {
            val document = res.document
            // The watch-server list is rendered as buttons carrying the
            // embed URL in data-embed-url (verified live on هند خانم ep46:
            // hdup400 + ok.ru buttons; the raw-URL sweep below drops
            // ok.ru's /videoembed/ path, so this pass is the reliable one).
            document.select("[data-embed-url]").forEach {
                val u = it.attr("data-embed-url").trim()
                if (u.startsWith("http") && !u.startsWith(mainUrl)) candidates += fixUrl(u)
            }
            document.select("iframe[src]").forEach {
                val src = it.attr("src").trim()
                if (src.startsWith("http") && !src.contains(mainUrl)) candidates += fixUrl(src)
            }
            document.select("a[href]").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http") && !href.startsWith(mainUrl) &&
                    (href.contains("/e/") || href.contains("/embed"))
                ) candidates += fixUrl(href)
            }
            // The server list is also rendered as raw URLs in the page body.
            Regex("""https?://[^\s"'<>\\]+""").findAll(res.text).forEach { m ->
                val u = m.value
                if (!u.startsWith(mainUrl) && (u.contains("/e/") || u.contains("/embed")) &&
                    !u.contains("google") && !u.contains("facebook") && !u.contains("aglo") &&
                    !u.contains(".js") && !u.contains(".css") && !u.contains(".png") && !u.contains(".jpg")
                ) candidates += fixUrl(u)
            }
        }
        diag += "embeds=${candidates.size}"

        val ordered = orderEmbeds(candidates)
        // Stage 1: the app's host-based extractors. v5: each call is
        // isolated — an extractor that THROWS on a junk candidate from the
        // raw-URL sweep (or on a host the user's network resets) used to
        // abort the whole chain before the dig stages ever ran, which is
        // how an episode with a perfectly good hdup400 server ended at
        // "link not found".
        var found = false
        val unresolved = mutableListOf<String>()
        for (embed in ordered) {
            val ok = runCatching {
                loadExtractor(embed, data, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) {
                found = true
            } else {
                unresolved += embed
            }
        }
        // Stage 2: dig the direct file out of EVERY unresolved embed page
        // on the phone itself — v5 no longer gates this on stage 1 having
        // found nothing: an extractor can claim a server (ok.ru) and still
        // hand back a link that never plays, and that must not block the
        // hdup400 dig behind it.
        var scanned = 0
        val dugOk = HashSet<String>()
        for (embed in unresolved) {
            if (scanEmbed(embed, data, callback)) {
                scanned++
                dugOk += embed
                found = true
            }
        }
        diag += "scan=$scanned"
        // Stage 3: the relay server takes the same dig from its side for
        // whatever is still unresolved.
        var relayed = 0
        for (embed in unresolved.filter { it !in dugOk }.take(3)) {
            relayed += resolveViaRelay(embed, data, callback)
        }
        diag += "relay=$relayed"
        if (relayed > 0) found = true
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
                    "DEBUG Brestj v8 | " + diag.joinToString(" | "),
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
