package com.wecimamobile

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * WeCima provider for CloudStream 3 — a FRESH, single-domain build on
 * https://wecima.mobile, written 2026-10-16 at Mohamed's order after
 * the three-domain WeCima provider accumulated too many patches.
 *
 * Everything is traced live from the site itself (2026-10-16):
 * - Custom (non-WordPress) theme; no Cloudflare wall for plain fetches.
 *   The catalog UNIFIES Arabic series/movies and foreign subtitled
 *   content. Cards are a.show-card anchors (real img poster + p.title).
 * - Posts: /episode/{slug} and /film/{slug} with RAW Arabic slugs.
 *   Categories: /category/{arabic-slug}; home + categories paginate
 *   ?page={n}; search is /search?s={urlencoded q}.
 * - An episode page links its series' sibling episodes directly; the
 *   same-series set shares the slug stem (text before -الحلقة).
 * - Playback protocol: the post links /watch/{same slug}; the watch
 *   page carries `const servers = [...]` (12-14 standard hosts:
 *   earnvids, fastved, vinovo, m1xdrop, doodstream, voe, vidaraa,
 *   uqload, byse, vids, savefiles, vikingfile, ok, streamtape...),
 *   a watchResolveKey and a csrfToken, and sets session cookies.
 *   POST /watch/resolve {key, index} with the session cookies and the
 *   X-CSRF-TOKEN header answers {ok, url:"/m/{hash}"}; the fragment
 *   302 / meta-refreshes to the real host embed.
 *
 * Design rules for this build (lessons of the previous provider):
 * - ONE domain, one chain. No fallbacks to other domains, no twin
 *   redirects, no background prefetch, no parallel resolve storms,
 *   no response caches, no pacing gates — plain sequential requests
 *   exactly like the chain Mohamed confirmed working ("اشتغل",
 *   2026-10-16 12:09).
 * - EVERY server on the watch page is resolved and offered (never
 *   stop at the first one or two — Mohamed's standing rule).
 * - Browser headers on every request from v1.
 * - Any URL placed in a header is percent-encoded first: the site's
 *   slugs are raw Arabic and okhttp throws IllegalArgumentException
 *   on non-ASCII header values (the v12 root-cause fix, kept here).
 * - Every chain ends at an extractor-known host or a direct file:
 *   extractor -> phone-side scan (raw + packed-JS unpack) -> relay
 *   resolver. A DEBUG readout is emitted when nothing playable is
 *   found, so a failure names its own stage.
 */
class WeCimaMobileProvider : MainAPI() {
    // v2 (2026-11-03): operator moved the mobile theme to weciema.net.
    override var mainUrl = "https://weciema.net"

    // WordPress backend: the addon's primary source since v7 (the
    // mobile host blocks the phone's IP) — catalog, search, playback
    // fallback; mobile stays only as a backup.
    private val wpUrl = "https://wecima.vin"
    private val wpAltUrl = "https://wecima.tattoo"
    override var name = "WeCima"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "ar"
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
        "$mainUrl/" to "المضاف حديثا",
        "$mainUrl/category/افلام-اجنبي" to "أفلام أجنبية",
        "$mainUrl/category/افلام-عربي" to "أفلام عربية",
        "$mainUrl/category/افلام-هندي" to "أفلام هندية",
        "$mainUrl/category/افلام-تركية" to "أفلام تركية",
        "$mainUrl/category/افلام-اسيوية" to "أفلام آسيوية",
        "$mainUrl/category/افلام-انمي" to "أفلام أنمي",
        "$mainUrl/category/مسلسلات-عربي" to "مسلسلات عربية",
        "$mainUrl/category/مسلسلات-اجنبي" to "مسلسلات أجنبية",
        "$mainUrl/category/مسلسلات-تركية" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-اسيوية" to "مسلسلات آسيوية",
        "$mainUrl/category/مسلسلات-هندية" to "مسلسلات هندية",
        "$mainUrl/category/مسلسلات-مدبلجة" to "مسلسلات مدبلجة",
        "$mainUrl/category/مسلسلات-انمي" to "مسلسلات أنمي",
        "$mainUrl/category/برامج-تلفزيونية" to "برامج تلفزيونية",
        "$mainUrl/category/عروض-مصارعة" to "عروض مصارعة",
        "$mainUrl/category/مسلسلات-رمضان-2026" to "مسلسلات رمضان 2026",
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

    /**
     * Percent-encodes any non-ASCII characters of a URL string. The
     * site's anchors carry RAW Arabic slugs; okhttp percent-encodes a
     * URL in the request line by itself, but a raw-Arabic string used
     * as a HEADER value (referer) throws IllegalArgumentException
     * before any request is sent. Every header-bound URL goes through
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

    // ---------- cards ----------

    private fun Element.toMobileCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (!href.contains("/episode/") && !href.contains("/film/")) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("src"))?.takeIf { it.startsWith("http") }
            ?: fixUrlNull(img?.attr("data-src"))
        val title = cleanTitle(
            selectFirst("p.title")?.text() ?: img?.attr("alt") ?: attr("title")
        )
        if (title.isBlank()) return null
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val anime = isAnime(decode(href))
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

    // ---------- WordPress cards (v7: twin sections) ----------

    private fun Element.toWpCard(): SearchResponse? {
        val a = selectFirst(".Thumb--GridItem a") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.startsWith(wpUrl) && !href.startsWith(wpAltUrl)) return null
        if (href.contains("/category/") || href.contains("/wp-") ||
            href.endsWith("/movies/") || href.endsWith("/series/") ||
            href.endsWith("/episodes/") || href == "$wpUrl/" || href == "$wpAltUrl/"
        ) return null
        val title = cleanTitle(a.attr("title").ifBlank { a.text() })
        if (title.isBlank()) return null
        val style = selectFirst("span[data-lazy-style]")?.attr("data-lazy-style") ?: ""
        var poster = Regex("""url\((https?://[^)]+)\)""").find(style)?.groupValues?.get(1)
            ?.let { fixUrlNull(it) }
        if (poster == null) {
            val img = selectFirst("img")
            poster = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))
                // "loading" (placeholder), NOT "load": every WP poster
                // lives under /wp-content/uploads/, which contains
                // "load" — the old filter dropped all of them (v8).
                ?.takeIf { !it.contains("loading") && !it.contains("blank") }
        }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val decoded = decode(href)
        val anime = isAnime(decoded)
        val seriesLike = href.contains("/series/") || decoded.contains("مسلسل") ||
            decoded.contains("الحلقة") || decoded.contains("حلقة")
        return when {
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

    private fun Document.parseWpCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select(".GridItem").forEach { item ->
            val card = runCatching { item.toWpCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(card.url, card)
        }
        if (seen.isEmpty()) {
            select("a[href][title]").forEach { a ->
                val card = runCatching { a.toWpCard() }.getOrNull() ?: return@forEach
                seen.putIfAbsent(card.url, card)
            }
        }
        return seen.values.toList()
    }

    // Maps a mobile section URL to its WordPress twin (v7): the
    // operator's WP site carries the same categories (verified live),
    // with two slug renames.
    private fun wpSectionTwin(url: String): String? {
        val base = url.trimEnd('/').substringBefore("?page=")
        if (base == mainUrl) return "$wpUrl/"
        if (!base.startsWith("$mainUrl/category/")) return null
        val slug = base.removePrefix("$mainUrl/category/")
        val mapped = when (slug) {
            "افلام-اسيوية" -> "افلام-اسيوي"
            "مسلسلات-عربي" -> "مسلسلات-عربية"
            else -> slug
        }
        return "$wpUrl/category/$mapped/"
    }

    // ---------- browse / search ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // v7: the addon is based on wecima.vin now — the mobile host
        // blocks the phone's IP, so sections come from the WordPress
        // twin first (then its alt host), mobile only as a backup.
        // The site paginates home + categories with ?page={n}.
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else "$base?page=$page"
        var items: List<SearchResponse> = emptyList()
        if (page <= 1) {
            wpSectionTwin(url)?.let { twin ->
                for (candidate in listOf(twin, twin.replace(wpUrl, wpAltUrl))) {
                    val wpItems = runCatching {
                        app.get(candidate, headers = browserHeaders).document.parseWpCards()
                    }.getOrNull().orEmpty()
                    if (wpItems.isNotEmpty()) {
                        items = wpItems
                        break
                    }
                }
            }
        }
        if (items.isEmpty()) {
            items = runCatching {
                app.get(url, headers = browserHeaders).document.parseMobileCards()
            }.getOrNull().orEmpty()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // v7: WordPress first (the working host), mobile second, one
        // entry per title (WP wins ties).
        val seen = LinkedHashMap<String, SearchResponse>()
        val wpItems = runCatching {
            app.get(
                "$wpUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8"),
                headers = browserHeaders
            ).document.parseWpCards()
        }.getOrNull().orEmpty()
        wpItems.forEach { seen.putIfAbsent(searchKey(it.name), it) }
        val mobItems = runCatching {
            app.get(
                mainUrl + "/search?s=" + java.net.URLEncoder.encode(query, "UTF-8"),
                headers = browserHeaders
            ).document.parseMobileCards()
        }.getOrNull().orEmpty()
        mobItems.forEach { seen.putIfAbsent(searchKey(it.name), it) }
        return seen.values.toList()
    }

    private fun searchKey(title: String): String {
        var t = title.trim()
        t = t.removePrefix("مشاهدة ").removePrefix("مسلسل ").removePrefix("فيلم ")
        return t.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ة", "ه").replace(" ", "").replace("-", "")
    }

    // ---------- title page ----------

    private fun mobileSeriesTitle(decodedSlug: String): String {
        val stem = decodedSlug.substringBefore("-الحلقة").substringBefore("-الموسم")
        return stem.removePrefix("مسلسل-").removePrefix("انمي-")
            .replace('-', ' ').trim()
    }

    // Trailer (v2, 2026-11-14, user-tested): a trailer button on
    // every detail page, from a free YouTube search. Desktop UA is
    // REQUIRED — the mobile search page returns no parseable
    // results. The setter wants a MutableList. v2 sanitizes the
    // title to a trailer NAME, searches Latin first then Arabic,
    // and only accepts a result whose normalized title contains
    // the name AND a trailer word. No match = no trailer (better
    // than a wrong one). Digits-only guard: a Latin candidate is
    // valid only if it contains at least one [A-Za-z] letter — an
    // Arabic-only title leaves only digits behind ("2 - 1").
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


    // v3 (2026-11-14, user order): trailers ONLY for Latin titles.
    // An Arabic-only title gets the poster only — no YouTube query
    // at all. A Latin candidate is valid only if it contains at
    // least one [A-Za-z] letter (digits-only candidates like "2"
    // are NOT titles). Queries are Latin-only; the pick accepts
    // only a result whose title contains a Latin candidate AND
    // a trailer word. No match = no trailer button.
    private suspend fun trailerFor(
        title: String,
        isSeries: Boolean = false,
        acceptPv: Boolean = false
    ): MutableList<TrailerData> {
        return try {
            val year = Regex("""(19|20)\d\d""").find(title)?.value
            val latins = latinTitleCandidates(title)
                .filter { cand -> cand.any { it in 'A'..'Z' || it in 'a'..'z' } }
            if (latins.isEmpty()) return mutableListOf()
            val queries = ArrayList<String>()
            for (cand in latins) {
                queries += if (isSeries) "$cand official trailer"
                else listOfNotNull(cand, year, "official trailer").joinToString(" ")
            }
            for (q in queries) {
                val id = youtubeTrailerId(q, latins, acceptPv) ?: continue
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

    private suspend fun youtubeTrailerId(
        query: String,
        names: List<String>,
        acceptPv: Boolean = false
    ): String? {
        return try {
            val url = "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val html = app.get(url, headers = trailerHeaders).text
            if (html.isBlank()) return null
            val idRe = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
            val runsRe =
                Regex(""""title":\{"runs":\[\{"text":"([^"]{3,120})"""")
            val simpleRe =
                Regex(""""title":\{"simpleText":"([^"]{3,120})"""")
            val lcNames = names.map { it.lowercase(java.util.Locale.ROOT) }
                .filter { it.length >= 3 }
            if (lcNames.isEmpty()) return null
            val seen = HashSet<String>()
            for (m in idRe.findAll(html)) {
                val id = m.groupValues[1]
                if (!seen.add(id)) continue
                val end = minOf(html.length, m.range.last + 1200)
                val window = html.substring(m.range.last, end)
                val t = runsRe.find(window)?.groupValues?.get(1)
                    ?: simpleRe.find(window)?.groupValues?.get(1)
                    ?: continue
                val lt = t.lowercase(java.util.Locale.ROOT)
                if (lcNames.none { lt.contains(it) }) continue
                val isTrailer = lt.contains("trailer") || lt.contains("teaser") ||
                    (acceptPv && t.contains("PV"))
                if (isTrailer) return id
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = runCatching { app.get(url, headers = browserHeaders) }.getOrNull()
        val document = res?.takeIf { it.code == 200 }?.document
        val rawTitle = document?.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document?.title()
        val title = rawTitle?.let { cleanTitle(it) }.orEmpty()
        // The mobile host rate-blocks the phone's IP (403): instead of
        // "No title" + retry, return a minimal response from the slug
        // so the detail screen opens and playback (with its own WP
        // fallback) still works.
        if (document == null || title.isBlank()) return loadFromSlug(url)
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.trim()?.ifBlank { null }
        val anime = isAnime(decode(url))

        // A WP episode post opens its whole series, not a lone film:
        // the WP episode set for the series (REST) becomes the
        // episode list. Falls through to the film response when the
        // set has < 2 episodes.
        if ((url.startsWith(wpUrl) || url.startsWith(wpAltUrl)) &&
            (Regex("""حلقة\s*-?\s*\d{1,4}""").containsMatchIn(decode(url)) ||
                Regex("""حلقة\s*-?\s*\d{1,4}""").containsMatchIn(title))
        ) {
            wpEpisodeSeries(url, title, poster, plot, anime)?.let { return it }
        }

        return if (url.contains("/episode/")) {
            // The episode page links its series' other episodes; the
            // same-series set shares this slug's stem (verified live:
            // an ep3 page links eps 1-4 of itself, alongside other
            // shows' latest episodes, which the stem filter drops).
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
                if (href.startsWith(mainUrl)) addEpisode(href)
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
                this.trailers = trailerFor(mobileSeriesTitle(decode(mySlug)).ifBlank { title }, isSeries = true, acceptPv = anime)
            }
        } else {
            newMovieLoadResponse(
                title, url,
                if (anime) TvType.Anime else TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.trailers = trailerFor(title, isSeries = false, acceptPv = anime)
            }
        }
    }

    /** Minimal detail from the URL slug, used when the page itself
     * is blocked: enough for the detail screen + playback to work. */
    private suspend fun loadFromSlug(url: String): LoadResponse {
        val slug = decode(url.trimEnd('/').substringAfterLast("/"))
        val clean = cleanTitle(slug.replace('-', ' '))
        val anime = isAnime(decode(url))
        return if (url.contains("/episode/")) {
            val num = Regex("""حلقة\s*-?\s*(\d{1,4})""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
            val ep = newEpisode(url) {
                this.name = num?.let { "الحلقة $it" } ?: clean
                this.season = seasonOf(clean)
                this.episode = num
            }
            newTvSeriesLoadResponse(
                mobileSeriesTitle(slug).ifBlank { clean }, url,
                if (anime) TvType.Anime else TvType.TvSeries,
                listOf(ep)
            ) {
                this.trailers = trailerFor(mobileSeriesTitle(slug).ifBlank { clean }, isSeries = true, acceptPv = anime)
            }
        } else {
            if (Regex("""حلقة\s*-?\s*\d{1,4}""").containsMatchIn(slug)) {
                wpEpisodeSeries(url, null, null, null, anime)?.let { return it }
            }
            newMovieLoadResponse(
                clean.ifBlank { url }, url,
                if (anime) TvType.Anime else TvType.Movie,
                url
            ) {
                this.trailers = trailerFor(clean.ifBlank { url }, isSeries = false, acceptPv = anime)
            }
        }
    }

    /** Groups a WP episode post into its series via the open WP REST
     * API. WP episode slugs are unreliable identifiers: long Arabic
     * titles get TRUNCATED (verified live: طالع نازل subtitle posts
     * lose the word الحلقة entirely, ending in a bare -N), and
     * /series/ hub pages list other shows' latest episodes while WP
     * search does not paginate — so REST + TITLE matching is the
     * only source returning the series' whole WP episode set.
     * The series identity comes from the post TITLE (series part
     * before الحلقة N), and candidates match when their series part
     * equals it or differs only by season words (الموسم الاول...),
     * which is what separates طالع نازل from طالع نازل: الحب اللي كان
     * while still admitting E4, whose title drops الموسم الاول.
     * Returns null when the set has < 2 episodes, so the caller
     * keeps the single-film response. */
    private suspend fun wpEpisodeSeries(
        url: String, displayTitle: String?, poster: String?, plot: String?, anime: Boolean
    ): LoadResponse? {
        val slugWords = decode(url.trimEnd('/').substringAfterLast("/")).replace('-', ' ')
        val tappedTitle = displayTitle?.ifBlank { null } ?: slugWords
        if (Regex("""حلقة\s*-?\s*\d{1,4}""").find(tappedTitle) == null) return null
        val seriesTitle = seriesPartOf(tappedTitle)
        if (seriesTitle.isBlank()) return null
        val seasonWords = setOf(
            "الموسم", "الاول", "الثاني", "الثالث", "الرابع", "الخامس",
            "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي", "عشر"
        )
        val query = seriesTitle.split(" ")
            .filter { it !in seasonWords && it.toIntOrNull() == null }
            .joinToString(" ").ifBlank { seriesTitle }
        val tappedNorm = normSeries(seriesTitle)
        val byKey = LinkedHashMap<String, Episode>()
        for (page in 1..2) {
            val body = runCatching {
                app.get(
                    "$wpUrl/wp-json/wp/v2/posts?search=" +
                        java.net.URLEncoder.encode(query, "UTF-8") +
                        "&per_page=100&page=$page",
                    headers = browserHeaders
                ).text
            }.getOrNull() ?: break
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: break
            if (arr.length() == 0) break
            var added = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val postTitle = obj.optJSONObject("title")?.optString("rendered")
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.replace(Regex("&#\\d+;"), "")
                    ?.replace("&amp;", "&")?.trim() ?: continue
                val num = Regex("""حلقة\s*-?\s*(\d{1,4})""").find(postTitle)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val candSeries = seriesPartOf(postTitle)
                if (!seriesPartMatches(tappedNorm, normSeries(candSeries))) continue
                val season = seasonOf(postTitle)
                val key = "$season:$num"
                if (byKey.containsKey(key)) continue
                byKey[key] = newEpisode("$wpUrl/" + obj.optString("slug") + "/") {
                    this.name = "الحلقة $num"
                    this.season = season
                    this.episode = num
                }
                added++
            }
            if (added == 0) break
            if (arr.length() < 100) break
        }
        if (byKey.size < 2) return null
        val episodes = byKey.values.sortedWith(
            compareBy({ it.season ?: 1 }, { it.episode ?: 0 })
        )
        return newTvSeriesLoadResponse(
            seriesTitle, url,
            if (anime) TvType.Anime else TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.trailers = trailerFor(seriesTitle, isSeries = true, acceptPv = anime)
        }
    }

    /** Series part of an episode title: strip the مشاهدة/مسلسل
     * prefix, then everything from the episode word on (which also
     * cuts the site suffix that follows it). */
    private fun seriesPartOf(title: String): String {
        var t = title.trim()
            .removePrefix("مشاهدة مسلسل ").removePrefix("مشاهدة ").removePrefix("مسلسل ")
            .trim()
        t = t.substringBefore(" الحلقة ").substringBefore(" حلقة ").trim()
        return t
    }

    /** Normalizes a series part for comparison: alef variants,
     * ة→ه, and punctuation/spacing dropped. */
    private fun normSeries(s: String): String =
        s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه")
            .replace(" ", "").replace("-", "").replace(":", "")
            .replace("–", "").replace("_", "").replace("&", "")
            .replace("\"", "").replace("'", "")

    /** True when two normalized series parts denote the same series:
     * equal, or one is a prefix of the other with the difference
     * consisting ONLY of season words (الموسم + ordinal). */
    private fun seriesPartMatches(tapped: String, cand: String): Boolean {
        if (tapped == cand) return true
        val seasonBits = listOf(
            "الحاديعشر", "الثانيعشر", "الموسم", "الاول", "الثاني",
            "الثالث", "الرابع", "الخامس", "السادس", "السابع",
            "الثامن", "التاسع", "العاشر"
        )
        fun seasonOnly(extra: String): Boolean {
            var e = extra
            for (w in seasonBits) e = e.replace(w, "")
            e = e.replace(Regex("\\d+"), "")
            return e.isEmpty()
        }
        if (cand.startsWith(tapped)) return seasonOnly(cand.substring(tapped.length))
        if (tapped.startsWith(cand)) return seasonOnly(tapped.substring(cand.length))
        return false
    }

    // ---------- playback ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val diag = mutableListOf("play")
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { emitted++; callback(it) }
        val found = runCatching { loadLinksMobile(data, diag, subtitleCallback, counting) }
            .getOrDefault(false)
        if (!found) emitDebug(diag, callback)
        return true
    }

    /**
     * The watch protocol, sequential end to end: watch page (session
     * cookies) -> one resolve POST per server -> fragment -> real
     * embed -> extractors -> scan -> relay. Mirrors exactly the chain
     * verified live from the site and confirmed on-device.
     */
    private suspend fun loadLinksMobile(
        data: String,
        diag: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Header-bound URLs are percent-encoded first (raw Arabic
        // slugs throw IllegalArgumentException as header values).
        val safeData = pctEncodeNonAscii(data)
        diag += "enc=1"
        val watchUrl = safeData.replace("/episode/", "/watch/").replace("/film/", "/watch/")

        // The host can be slow; retry the watch fetch with backoff
        // and record the exception class so a failure names itself.
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
        if (res == null || res.code != 200) {
            // The watch page itself is blocked (403 rate-limit): the
            // WordPress host is a different host — try it from the
            // episode slug instead of dying here.
            diag += "wpFb=1"
            if (wpFallbackFromSlug(data, callback)) {
                diag += "wpFbHit=1"
                return true
            }
            return false
        }

        val cookies = runCatching { res.cookies }.getOrNull().orEmpty()
        val text = res.text
        val key = Regex("""watchResolveKey\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        val csrf = Regex("""csrfToken\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        val serversJson = Regex("""const servers = (\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        val serverNames = mutableListOf<String>()
        val serverTokens = mutableListOf<String>()
        if (serversJson != null) {
            val arr = runCatching { org.json.JSONArray(serversJson) }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    serverNames += obj?.optString("name") ?: "server$i"
                    serverTokens += obj?.optString("resolve_token") ?: ""
                }
            }
        }
        diag += "servers=${serverNames.size}"
        if (key == null || csrf == null || serverNames.isEmpty()) {
            diag += "wpFb=1"
            if (wpFallbackFromSlug(data, callback)) {
                diag += "wpFbHit=1"
                return true
            }
            return false
        }

        // Resolve EVERY server, in the site's order, one at a time.
        // The site's own player posts {key, token, human_signal} — the
        // per-server resolve_token, not a positional index.
        val embeds = mutableListOf<String>()
        var gated = false
        for (i in serverNames.indices) {
            val token = serverTokens.getOrNull(i) ?: ""
            if (token.isBlank()) continue
            val (embed, isGated) = resolveOneMobile(token, key, csrf, cookies, watchUrl)
            // The gate is session-wide, not per-server: once one
            // server 409s, the rest will too — stop the burst here
            // instead of firing 16 doomed POSTs that invite a 403.
            if (isGated) { gated = true; break }
            if (embed != null && embed !in embeds) embeds += embed
            if (i < serverNames.size - 1) Thread.sleep(250)
        }
        diag += "resolved=${embeds.size}"
        if (gated) diag += "gate=409"
        if (embeds.isEmpty()) {
            // The mobile chain is gated (human verification): fall
            // back to the WordPress chain, which serves a plain HLS
            // stream with no gate, instead of the DEBUG test video.
            diag += "wpFb=1"
            if (wpFallbackFromTitle(text, callback)) {
                diag += "wpFbHit=1"
                return true
            }
            return false
        }

        // Stage 1: the app's own extractors.
        val unresolved = mutableListOf<String>()
        var extracted = 0
        for (embed in embeds) {
            val ok = runCatching {
                loadExtractor(embed, watchUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) extracted++ else unresolved += embed
        }
        diag += "embeds=$extracted"

        // Stage 2: dig the direct file out of a rejected embed page.
        val stillUnresolved = mutableListOf<String>()
        var scanned = 0
        for (embed in unresolved) {
            if (runCatching { scanEmbed(embed, data, callback) }.getOrDefault(false)) {
                scanned++
            } else {
                stillUnresolved += embed
            }
        }
        diag += "scan=$scanned"

        // Stage 3: the relay's generic resolver dig (first 3 only).
        var relayed = 0
        for (embed in stillUnresolved.take(3)) {
            relayed += runCatching { resolveViaRelay(embed, data, callback) }.getOrDefault(0)
        }
        diag += "relay=$relayed"

        return extracted > 0 || scanned > 0 || relayed > 0
    }

    /** One server: POST /watch/resolve, then follow its fragment to
     * the real embed URL. The resolve POST is retried once. The body
     * matches the site's own player: {key, token, human_signal} with
     * a null signal (the no-fingerprint case). Returns the embed plus
     * whether the site answered with its human-verification gate. */
    private suspend fun resolveOneMobile(
        token: String,
        key: String,
        csrf: String,
        cookies: Map<String, String>,
        watchUrl: String
    ): Pair<String?, Boolean> {
        val body = JSONObject().put("key", key).put("token", token)
            .put("human_signal", JSONObject.NULL).toString()
        val headers = browserHeaders + mapOf(
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "X-FP-Event-ID" to "",
            "Accept" to "application/json"
        )
        var fragPath: String? = null
        var gated = false
        for (attempt in 1..2) {
            val rText = runCatching {
                app.post(
                    "$mainUrl/watch/resolve",
                    headers = headers,
                    cookies = cookies,
                    referer = watchUrl,
                    requestBody = body.toRequestBody("application/json".toMediaType())
                ).text
            }.getOrNull()
            if (rText != null && rText.contains("verification_url")) gated = true
            if (gated) break
            fragPath = rText?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optBoolean("ok") }?.optString("url")?.takeIf { it.isNotBlank() }
            if (fragPath != null) break
            Thread.sleep(700)
        }
        val frag = fragPath ?: return null to gated
        val fragUrl = if (frag.startsWith("http")) frag else mainUrl + frag
        val fres = runCatching {
            app.get(fragUrl, headers = browserHeaders, cookies = cookies, referer = watchUrl)
        }.getOrNull() ?: return null to gated
        val finalUrl = fres.url
        if (finalUrl.startsWith("http") && !finalUrl.contains("wecima.mobile") && !finalUrl.contains("weciema.net")) {
            return finalUrl to gated
        }
        val t = runCatching { fres.text }.getOrNull() ?: return null to gated
        return (Regex("""url='([^']+)'""").find(t)?.groupValues?.get(1)
            ?: Regex("""<a href="([^"]+)"""").find(t)?.groupValues?.get(1)) to gated
    }

    /**
     * WordPress fallback: when the mobile resolve chain is gated, find
     * the same episode on wecima.vin by title and play its mycima-my
     * redirector stream (plain HLS, no gate). Returns true on emit.
     */
    private suspend fun wpFallback(
        clean: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (clean.isBlank()) return false
        val series = clean.substringBefore(" الحلقة ").substringBefore(" حلقة ").trim()
        val epNum = Regex("""حلقة\s*-?\s*(\d{1,4})""").find(clean)?.groupValues?.get(1)
        if (series.isBlank()) return false
        // An episode whose number cannot be read must not guess:
        // playing the wrong episode is worse than DEBUG.
        if (clean.contains("حلقة") && epNum == null) return false
        val norm: (String) -> String = {
            it.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ة", "ه").replace(" ", "").replace("-", "")
        }
        val seriesNorm = norm(series)
        // WP episode slugs come in two spellings: الحلقة-N (early
        // episodes) and bare حلقة-N (later ones, e.g. سيدو وحورية
        // E7/E8). The bare form is a substring of the ال form, so one
        // bare needle matches both — with a digit boundary so E7
        // never matches E70/E17.
        val epNeedle = if (epNum != null) Regex("""حلقه$epNum(?!\d)""") else null
        val queries = mutableListOf<String>()
        if (epNum != null) queries += "$series الحلقة $epNum"
        queries += series
        for (query in queries) {
            val searchText = runCatching {
                app.get(
                    "$wpUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8"),
                    headers = browserHeaders
                ).text
            }.getOrNull() ?: continue
            // Any link on the WP search page whose slug holds the
            // series words (and the episode number, when known).
            val hrefs = Regex("""<a[^>]+href="([^"]+)"[^>]*>""")
                .findAll(searchText).map { it.groupValues[1] }.toList()
            for (href in hrefs) {
                if (!href.startsWith(wpUrl)) continue
                val slugRaw = href.trimEnd('/').substringAfterLast("/")
                val slug = runCatching {
                    java.net.URLDecoder.decode(slugRaw, "UTF-8")
                }.getOrNull() ?: slugRaw
                val slugNorm = norm(slug)
                if (!slugNorm.contains(seriesNorm)) continue
                if (epNeedle != null && !epNeedle.containsMatchIn(slugNorm)) continue
                val postUrl = fixUrl(href)
                val postText = runCatching {
                    app.get(postUrl, headers = browserHeaders).text
                }.getOrNull() ?: continue
                val redir = Regex("""data-watch="([^"]+)"""").find(postText)
                    ?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
                    ?: continue
                val redirUrl = fixUrl(redir)
                val redirText = runCatching {
                    app.get(redirUrl, headers = browserHeaders, referer = postUrl).text
                }.getOrNull() ?: continue
                val direct = extractDirect(redirText)
                    ?: unpackPackedJs(redirText).firstNotNullOfOrNull { extractDirect(it) }
                if (direct != null) {
                    emitDirect(direct, redirUrl, callback)
                    return true
                }
                // One nested iframe level: embed pages often frame
                // the real player (same coverage as scanEmbed).
                val nested = Regex("""<iframe[^>]+src="([^"]+)"""")
                    .find(redirText)?.groupValues?.get(1)
                    ?.takeIf { it.startsWith("http") }
                if (nested != null) {
                    val nestedText = runCatching {
                        app.get(fixUrl(nested), headers = browserHeaders, referer = redirUrl).text
                    }.getOrNull()
                    val nestedDirect = nestedText?.let {
                        extractDirect(it)
                            ?: unpackPackedJs(it).firstNotNullOfOrNull { d -> extractDirect(d) }
                    }
                    if (nestedDirect != null) {
                        emitDirect(nestedDirect, fixUrl(nested), callback)
                        return true
                    }
                }
            }
        }
        return false
    }

    /** WP fallback from a loaded watch page's <title>. */
    private suspend fun wpFallbackFromTitle(
        watchText: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawTitle = Regex("""<title>([^<]*)</title>""").find(watchText)
            ?.groupValues?.get(1)?.trim() ?: return false
        val clean = rawTitle.substringBefore(" - وي سيما").substringBefore(" | ").trim()
        return wpFallback(clean, callback)
    }

    /** WP fallback from the episode/film slug, used when the watch
     * page itself never loads (403 block page carries no title). */
    private suspend fun wpFallbackFromSlug(
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slugRaw = data.trimEnd('/').substringAfterLast("/")
        if (slugRaw.isBlank()) return false
        val slug = runCatching {
            java.net.URLDecoder.decode(slugRaw, "UTF-8")
        }.getOrNull() ?: slugRaw
        val clean = slug.replace('-', ' ').trim()
        return wpFallback(clean, callback)
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

    /** HLS playlists on these hosts hide behind .txt names and /hls*
     * path segments (verified live: .../hls3/.../master.txt serves
     * application/vnd.apple.mpegurl, referer-gated, no cookies
     * needed). Emitting one as VIDEO makes the player fail with
     * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) — the type,
     * not the URL, is the bug. */
    private fun isHlsUrl(url: String): Boolean =
        url.contains(".m3u8") || url.contains("/hls") || url.contains("master.txt")

    /** Emits one direct media URL found inside an embed page. */
    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                name, name, url,
                if (isHlsUrl(url)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    // Dean Edwards packer blocks: eval(function(p,a,c,k,e,d){...}('payload',
    // radix, count, 'k1|k2|...'.split('|'))). Several embed hosts hide
    // their player config this way; unpacked, it carries a plain
    // file:"..." URL. Final fallback of the scan above.
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
