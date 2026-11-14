package com.animeverse

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Miruro.tv — SvelteKit anime site, first AnimeVerse member.
 *
 * Chain traced live 2026-11-05 (no /api calls: the site's own
 * /api routes answer Cloudflare "Attention Required!" from
 * datacenter IPs, while the plain HTML pages answer 200):
 * - Home (v17): ALL of the site's sections, each named
 *   "<section> — Miruro" (standing rule: every member shows
 *   all its sections with its site name beside each, full
 *   sideways-scrolling content — no truncated rows):
 *   1. «الرائج» — GET /trending, SSR <article data-cover-card>
 *      cards (18, one season: Fall 2026).
 *   2. «أحدث الحلقات» — GET /__data.json (SvelteKit stream):
 *      rails.raw.latest (54) + rails.dub.latest (44), deduped
 *      by anime (max episode), cards named "Title — الحلقة N".
 *   3. «الأعلى مشاهدة» — same __data.json, sideLists.airing (10).
 *   4. «القادم» — same __data.json, sideLists.upcoming (10).
 *   5. «جدول العرض» — GET /schedule, SSR schedule-card anchors
 *      (whole week, 52 entries / 15 unique titles, posters).
 *   __data.json is devalue-serialised: the main object carries
 *   nodes (layout + home) whose data arrays are value tables
 *   (structural ints are indices; an int stored IN the table
 *   is a literal number), and rails/sideLists arrive as
 *   ["Promise", k] resolved by streamed {"type":"chunk"} objects.
 *   NOT shipped: Discover — its data comes from /api/search/browse
 *   (CF-403 from the sandbox, unverifiable here).
 * - Search: GET https://miruro.tv/search?q=<query> — SSR cards in
 *   .browse-grid: <a href="/info/..."><img src alt="Title">.
 *   (The client-side route /api/search/browse?q=&limit=8&type=ANIME
 *   exists in the JS bundles but is CF-gated from the sandbox.)
 * - Detail: GET https://miruro.tv/info/<id>/<slug> — JSON-LD
 *   (@type TVSeries vs Movie, name, description, genre) + an
 *   embedded "episodes:<N>" count. Episodes are watch links
 *   /watch/<id>/<slug>?ep=N (verified ?ep=1 pattern).
 *   The server 301-redirects /info/<id> (no slug) and any wrong
 *   slug to the canonical URL, so __data.json items (which carry
 *   no slug) use /info/<id> and load() reads the canonical slug
 *   from <link rel="canonical"> for the watch URL.
 * - Playback: GET https://miruro.tv/watch/<id>/<slug>?ep=N —
 *   server-rendered JS data: tracks:[{track:"sub"|"dub"|"ssub",
 *   providers:[{provider, servers:[{server, headers:{Referer},
 *   streams:[{url master.m3u8, format:"hls"}]}],
 *   subtitles:[{language, label, file ...vtt}]}]}].
 *   Verified on Reincarnated as a Sword S2 ep 1: provider "hop",
 *   servers Vid/Cat (krussdomi HLS + Referer), subtitles EN/ZH/ID/JA.
 *   No Arabic subtitle track exists on the site — the Arabic
 *   subtitle pipeline (search Arabic sub sites, else auto-translate
 *   the EN VTT, AR alone / EN alone / AR+EN merged) is a later
 *   phase, not part of v1.
 * - DEBUG markers in v1 (standing rule): loadLinks always emits one
 *   DEBUG Mux source carrying track/provider/stream/sub counts, so
 *   an empty on-phone source list means loadLinks never ran.
 * - No coroutine imports anywhere (CI classpath law).
 */
object MiruroSite : AnimeSite {
    override val host = "miruro.tv"
    // Standing rule (user, 2026-11-05): every member publishes ALL
    // its sections, each titled "<section> — <Site>", with the
    // section's FULL contents sideways-scrolling — never a
    // truncated row, never a bare section name.
    override val sections = listOf(
        "miruro:trending" to "الرائج — Miruro",
        "miruro:latest" to "أحدث الحلقات — Miruro",
        "miruro:airing" to "الأعلى مشاهدة — Miruro",
        "miruro:upcoming" to "القادم — Miruro",
        "miruro:schedule" to "جدول العرض — Miruro"
    )

    private const val mainUrl = "https://miruro.tv"
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val relayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private fun posterHeaders(): Map<String, String> {
        val out = HashMap<String, String>()
        out["User-Agent"] = headers["User-Agent"] ?: "Mozilla/5.0"
        out["Referer"] = "$mainUrl/"
        cloudflareKiller.savedCookies["miruro.tv"]?.let { cookies ->
            if (cookies.isNotEmpty()) {
                out["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }
        return out
    }

    private suspend fun fetchHtml(url: String): String {
        return try {
            app.get(url, headers = headers, interceptor = cloudflareKiller).text
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * v8: subtitle-file fetch. Plain request FIRST, exactly like the
     * player fetches the site's own subs (no interceptor, Referer
     * miruro.tv), CloudflareKiller as fallback. A fetch only counts
     * when the body parses into real cues — a challenge/error page
     * is non-empty but cue-less, and the old isNotEmpty guard let
     * it through (or hid the failure). The v5-v7 merged entry never
     * appeared on-device because fetchHtml (killer-only) returned ""
     * for the CF-walled English VTT.
     */
    /**
     * v10: subtitle-file fetch returning (usableText, rawLen).
     * usableText is "" unless the body parses into real cues; rawLen
     * is the byte length the last attempt returned (0 = nothing
     * fetched — distinguishes "fetch failed" from "fetched but
     * unparseable" in the DEBUG line).
     */
    private suspend fun fetchSubText(url: String): Pair<String, Int> {
        val plainHeaders = mapOf(
            "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
            "Referer" to "$mainUrl/",
            "Accept" to "*/*"
        )
        var rawLen = 0
        for (useKiller in listOf(false, true)) {
            try {
                val text = if (useKiller) fetchHtml(url)
                else app.get(url, headers = plainHeaders).text
                rawLen = text.length
                if (text.isNotEmpty() && parseSubCues(text).isNotEmpty()) return text to rawLen
            } catch (e: Exception) {
            }
        }
        return "" to rawLen
    }

    private suspend fun fetchSub(url: String): String = fetchSubText(url).first

    // ---------- cards ----------

    private val articleRegex = Regex(
        """<article[^>]*>(.*?)</article>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val scheduleCardRegex = Regex(
        """<a href="(/info/[^"]+)"[^>]*class="schedule-card[^>]*>(?:(?!</a>).)*?<img[^>]+src="([^"]+)"[^>]*>(?:(?!</a>).)*?<p class="truncate[^"]*">([^<]+)</p>(?:(?!</a>).)*?<p class="schedule-meta[^"]*">(.*?)</p>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val anchorCardRegex = Regex(
        """<a href="/info/([^"]+)"[^>]*>(?:(?!</a>).)*?<img[^>]+src="([^"]+)"[^>]*alt="([^"]*)"""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun cleanTitle(raw: String): String {
        return raw.replace("&amp;", "&").replace("&#39;", "'").trim()
    }

    private fun parseCards(api: MainAPI, html: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        val ph = posterHeaders()
        // Pass 1: <article> cards (trending page): overlay anchor
        // carries aria-label, poster img is a sibling inside article.
        for (block in articleRegex.findAll(html)) {
            val body = block.groupValues[1]
            val href = Regex("""href="(/info/[^"]+)"""").find(body)?.groupValues?.get(1) ?: continue
            val title = Regex("""aria-label="([^"]+)"""").find(body)?.groupValues?.get(1)
                ?.ifEmpty { null }
                ?: Regex("""alt="([^"]+)"""").find(body)?.groupValues?.get(1)?.ifEmpty { null }
                ?: continue
            val poster = Regex("""<img[^>]+src="([^"]+)"""").find(body)?.groupValues?.get(1)
            val url = api.fixUrl(href)
            if (!seen.add(url)) continue
            out.add(api.newTvSeriesSearchResponse(cleanTitle(title), url, TvType.Anime) {
                this.posterUrl = poster?.let { api.fixUrl(it) }
                this.posterHeaders = ph
            })
        }
        // Pass 3: schedule cards (/schedule): <a class="schedule-card"
        // href="/info/<id>/<slug>"> with <img poster> and a
        // <p class="truncate ...">Title</p> + "Ep N" meta. Runs
        // before Pass 2 so the real title wins over the slug fallback.
        for (m in scheduleCardRegex.findAll(html)) {
            val url = api.fixUrl(m.groupValues[1])
            if (!seen.add(url)) continue
            val title = cleanTitle(m.groupValues[3]).ifEmpty {
                m.groupValues[1].substringAfterLast('/').replace('-', ' ')
            }
            val ep = Regex("""Ep\s+(\d+)""").find(m.groupValues[4])?.groupValues?.get(1)
            val name = if (ep != null) "$title — الحلقة $ep" else title
            out.add(api.newTvSeriesSearchResponse(name, url, TvType.Anime) {
                this.posterUrl = m.groupValues[2].let { api.fixUrl(it) }
                this.posterHeaders = ph
            })
        }
        // Pass 2: anchor cards (search page): img inside the anchor.
        for (m in anchorCardRegex.findAll(html)) {
            val url = api.fixUrl("/info/" + m.groupValues[1])
            if (!seen.add(url)) continue
            val title = m.groupValues[3].ifEmpty {
                m.groupValues[1].substringAfterLast('/').replace('-', ' ')
            }
            out.add(api.newTvSeriesSearchResponse(cleanTitle(title), url, TvType.Anime) {
                this.posterUrl = api.fixUrl(m.groupValues[2])
                this.posterHeaders = ph
            })
        }
        return out
    }

    // ---------- home / search ----------

    /**
     * v17: Miruro's home data lives in GET /__data.json (SvelteKit
     * stream, devalue-serialised — see [devalueResolve]).
     * The stream is fetched once per refresh and cached 5 minutes,
     * so the three __data.json sections share one fetch.
     */
    private class HomeData(
        val latest: List<SearchResponse>,
        val airing: List<SearchResponse>,
        val upcoming: List<SearchResponse>
    )

    private var homeCache: HomeData? = null
    private var homeCacheAt: Long = 0

    private fun devalueRef(table: JSONArray, i: Int): Any? {
        if (i < 0 || i >= table.length()) return null
        val v = table.opt(i)
        // An int stored IN the table is a literal number, not a ref.
        if (v is Number) return v
        return devalueValue(table, v)
    }

    private fun devalueValue(table: JSONArray, v: Any?): Any? {
        return when (v) {
            is JSONArray -> {
                val out = ArrayList<Any?>()
                for (i in 0 until v.length()) {
                    val e = v.opt(i)
                    out.add(if (e is Number) devalueRef(table, e.toInt()) else devalueValue(table, e))
                }
                out
            }
            is JSONObject -> {
                val out = LinkedHashMap<String, Any?>()
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val e = v.opt(k)
                    out[k] = if (e is Number) devalueRef(table, e.toInt()) else devalueValue(table, e)
                }
                out
            }
            else -> v
        }
    }

    private fun jsonObj(v: Any?): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?>
    }

    private fun jsonList(v: Any?): List<Any?>? {
        @Suppress("UNCHECKED_CAST")
        return v as? List<Any?>
    }

    private fun titleOf(item: Map<String, Any?>): String? {
        val t = jsonObj(item["title"]) ?: return null
        val en = (t["english"] as? String)?.trim()?.ifEmpty { null }
        val romaji = (t["romaji"] as? String)?.trim()?.ifEmpty { null }
        val native = (t["native"] as? String)?.trim()?.ifEmpty { null }
        return en ?: romaji ?: native
    }

    private fun dataCard(api: MainAPI, item: Map<String, Any?>, nameSuffix: String = ""): SearchResponse? {
        val id = (item["animeId"] as? String) ?: (item["id"] as? String) ?: return null
        val title = titleOf(item) ?: return null
        val poster = (item["coverUrl"] as? String)?.takeIf { it.isNotBlank() }
        val url = "$mainUrl/info/$id"
        return api.newTvSeriesSearchResponse(cleanTitle(title) + nameSuffix, url, TvType.Anime) {
            this.posterUrl = poster?.let { api.fixUrl(it) }
            this.posterHeaders = posterHeaders()
        }
    }

    private suspend fun homeData(api: MainAPI): HomeData? {
        val nowMs = System.currentTimeMillis()
        homeCache?.let { if (nowMs - homeCacheAt < 5 * 60 * 1000) return it }
        return try {
            val text = fetchHtml("$mainUrl/__data.json")
            if (text.isBlank()) return null
            val chunks = HashMap<Int, JSONArray>()
            var mainTable: JSONArray? = null
            for (line in text.lines()) {
                val t = line.trim()
                if (t.isEmpty() || !t.startsWith("{")) continue
                val obj = JSONObject(t)
                when (obj.optString("type")) {
                    "data" -> {
                        val nodes = obj.optJSONArray("nodes") ?: continue
                        for (i in 0 until nodes.length()) {
                            val node = nodes.optJSONObject(i) ?: continue
                            if (node.optString("type") != "data") continue
                            val data = node.optJSONArray("data") ?: continue
                            val head = data.optJSONObject(0) ?: continue
                            if (head.has("rails")) mainTable = data
                        }
                    }
                    "chunk" -> {
                        val id = obj.optInt("id", -1)
                        if (id >= 0) obj.optJSONArray("data")?.let { chunks[id] = it }
                    }
                }
            }
            val table = mainTable ?: return null
            val root = jsonObj(devalueValue(table, table.optJSONObject(0))) ?: return null
            fun unpromise(v: Any?): Any? {
                val list = jsonList(v) ?: return v
                if (list.size == 2 && list[0] == "Promise") {
                    val cid = (list[1] as? Number)?.toInt() ?: return null
                    val chunk = chunks[cid] ?: return null
                    return devalueValue(chunk, chunk.optJSONObject(0))
                }
                return v
            }
            val rails = jsonObj(unpromise(root["rails"]))
            val side = jsonObj(unpromise(root["sideLists"]))
            // Latest Episodes: rails.raw.latest (54) + rails.dub.latest
            // (44), deduped by anime keeping the highest episode.
            val latestById = LinkedHashMap<String, Pair<Map<String, Any?>, Int>>()
            for (track in listOf("raw", "dub")) {
                val items = jsonList(jsonObj(rails?.get(track))?.get("latest")) ?: continue
                for (rawItem in items) {
                    val item = jsonObj(rawItem) ?: continue
                    val id = (item["animeId"] as? String) ?: continue
                    val ep = (item["episode"] as? Number)?.toInt() ?: 0
                    val prev = latestById[id]
                    if (prev == null || ep > prev.second ||
                        (ep == prev.second && (item["track"] as? String) == "dub")
                    ) latestById[id] = item to ep
                }
            }
            val latest = ArrayList<SearchResponse>()
            for ((item, ep) in latestById.values) {
                val suffix = " — الحلقة $ep" + if ((item["track"] as? String) == "dub") " (مدبلج)" else ""
                dataCard(api, item, suffix)?.let { latest.add(it) }
            }
            fun sideCards(key: String): List<SearchResponse> {
                val out = ArrayList<SearchResponse>()
                for (rawItem in jsonList(side?.get(key)) ?: emptyList()) {
                    val item = jsonObj(rawItem) ?: continue
                    dataCard(api, item)?.let { out.add(it) }
                }
                return out
            }
            val built = HomeData(latest, sideCards("airing"), sideCards("upcoming"))
            homeCache = built
            homeCacheAt = nowMs
            built
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun homeSection(api: MainAPI, data: String): List<SearchResponse> {
        return when (data) {
            "miruro:trending" -> parseCards(api, fetchHtml("$mainUrl/trending"))
            "miruro:schedule" -> parseCards(api, fetchHtml("$mainUrl/schedule"))
            "miruro:latest" -> homeData(api)?.latest ?: emptyList()
            "miruro:airing" -> homeData(api)?.airing ?: emptyList()
            "miruro:upcoming" -> homeData(api)?.upcoming ?: emptyList()
            else -> emptyList()
        }
    }

    override suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        return parseCards(api, fetchHtml("$mainUrl/search?q=$q"))
    }

    // ---------- load ----------

    private fun jsonString(html: String, key: String): String? {
        val m = Regex(
            """"$key":"((?:[^"\\]|\\.)*)""""
        ).find(html) ?: return null
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .trim()
            .ifEmpty { null }
    }

    override suspend fun load(api: MainAPI, url: String): LoadResponse {
        val html = fetchHtml(url)
        // The server 301-redirects /info/<id> (no slug) and any wrong
        // slug to the canonical URL — __data.json items carry no slug,
        // so read it back from <link rel="canonical"> and use it for
        // the watch URL (loadLinks builds /watch/<id>/<slug>?ep=N).
        val canonical = Regex("""rel="canonical" href="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.contains("/info/") } ?: url
        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore(" - Miruro")?.trim()?.ifEmpty { null }
            ?: jsonString(html, "name")
            ?: canonical.substringAfterLast('/').replace('-', ' ')
        val cover = Regex(
            """https://s4\.anilist\.co/file/anilistcdn/media/anime/cover/large/[^"\\]+"""
        ).find(html)?.value
        val poster = jsonString(html, "image")?.takeIf { it.contains("anilistcdn/media/anime/cover") }
            ?: cover
        val plot = jsonString(html, "description")
        val isMovie = html.contains("\"@type\":\"Movie\"")
        val watchUrl = canonical.replace("/info/", "/watch/")
        if (isMovie) {
            return api.newMovieLoadResponse(cleanTitle(title), canonical, TvType.AnimeMovie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.trailers = AnimeSubs.trailerFor(cleanTitle(title), isSeries = false, acceptPv = true)
            }
        }
        val epCount = Regex("""episodes:(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episodes = (1..epCount).map { n ->
            api.newEpisode("$watchUrl?ep=$n") {
                this.name = "الحلقة $n"
                this.episode = n
            }
        }
        return api.newTvSeriesLoadResponse(cleanTitle(title), canonical, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.trailers = AnimeSubs.trailerFor(cleanTitle(title), isSeries = true, acceptPv = true)
        }
    }

    // ---------- Arabic subtitles (v3) ----------

    /**
     * v3: Arabic subtitle search, no relay and no API key — the
     * same free route Merged uses, adapted to anime episodes:
     * IMDb suggest (v2.sg.media-imdb.com, no key) resolves the
     * series title to a tt id, then the OpenSubtitles v3 Strem
     * endpoint serves the episode's subtitles:
     * /subtitles/series/<tt>:<season>:<episode>.json (episodes)
     * or /subtitles/movie/<tt>.json (films). Arabic = first ara
     * file, UTF-8 preferred. Verified live 2026-11-05: Naruto
     * S1E1 -> 42 subtitles incl. 5 Arabic (UTF-8).
     * Fail-soft by design: any failure just skips the العربية
     * entry — playback and the site's own subtitles are untouched.
     * NOT in v3 yet: auto-translation of the EN VTT and the
     * AR+EN merged file — both need a relay endpoint that does
     * not exist yet (the old /relay/mergesubs answers 404 on the
     * deployed relay) plus a way to redeploy the Deno relay.
     */
    private fun slugTitle(data: String): Triple<String, Int, Int?> {
        val slug = data.substringAfter("/watch/").substringBefore("?").substringAfterLast("/")
        val season = Regex("""(?i)-season-(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val full = slug.replace('-', ' ').trim()
        val base = full.replace(Regex("""(?i)\s+season\s+\d+$"""), "").trim()
        return Triple(base, season, episode)
    }

    private suspend fun suggestImdbId(query: String, wantSeries: Boolean): String? {
        return try {
            val bucket = query.firstOrNull()?.lowercaseChar() ?: return null
            val q = URLEncoder.encode(query, "UTF-8")
            val text = app.get("https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json").text
            val arr = org.json.JSONObject(text).optJSONArray("d") ?: return null
            var fallback: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (!id.startsWith("tt")) continue
                if (fallback == null) fallback = id
                val kind = o.optString("q")
                if (wantSeries && kind == "TV series") return id
                if (!wantSeries && kind == "feature") return id
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun arabicSubFor(data: String): String? = osSubFor(data, "ara")

    /**
     * v12: ALL OpenSubtitles URLs for the title in a language (cap 5).
     * The relay scores each Arabic candidate against the English file
     * by timing coverage and merges the best-aligned one — the first
     * Arabic file is often the wrong-timed one (Naruto S1E1: first
     * 74% coverage starting at 108s, second 95% starting at 4s).
     */
    private suspend fun osSubsFor(data: String, lang: String): List<String> {
        return try {
            val (base, season, episode) = slugTitle(data)
            if (base.isEmpty()) return emptyList()
            val candidates = listOf(base).distinct()
            for (title in candidates) {
                val tt = suggestImdbId(title, episode != null) ?: continue
                val url = if (episode != null)
                    "https://opensubtitles-v3.strem.io/subtitles/series/$tt:$season:$episode.json"
                else
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json"
                val text = app.get(url).text
                val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: continue
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("lang") != lang) continue
                    val u = o.optString("url")
                    if (u.isBlank() || out.contains(u)) continue
                    out.add(u)
                    if (out.size >= 5) break
                }
                if (out.isNotEmpty()) return out
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * v10: OpenSubtitles track for the title in any language
     * ("ara" / "eng"), via IMDb suggest -> strem endpoint. Prefers a
     * UTF-8-encoded file, else the first track in that language.
     */
    private suspend fun osSubFor(data: String, lang: String): String? {
        return try {
            val (base, season, episode) = slugTitle(data)
            if (base.isEmpty()) return null
            val candidates = listOf(base).distinct()
            for (title in candidates) {
                val tt = suggestImdbId(title, episode != null) ?: continue
                val url = if (episode != null)
                    "https://opensubtitles-v3.strem.io/subtitles/series/$tt:$season:$episode.json"
                else
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json"
                val text = app.get(url).text
                val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: continue
                var firstLang: String? = null
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("lang") != lang) continue
                    val u = o.optString("url")
                    if (u.isBlank()) continue
                    if (firstLang == null) firstLang = u
                    if (o.optString("SubEncoding") == "UTF-8") return u
                }
                if (firstLang != null) return firstLang
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- subtitle content: merge + translate, in-addon (v4) ----------

    /**
     * v5 (2026-11-05): subtitle layer, final shape.
     * - Merge + translate run ON THE PHONE (only it can fetch the
     *   site's own subs — the relay gets eng: 403 from Deno).
     * - The finished VTT is POSTed to the relay /relay/store
     *   (Deno KV, 24h) and the returned https /s/<id> URL is the
     *   subtitle entry. data: URIs are GONE: the player drops them
     *   from the track list (v4 on-device report 07:46).
     * - Arabic files are often double-encoded UTF-8 (mojibake) and
     *   carry SRT <font> tags: both are repaired/stripped at parse
     *   time, here and in the relay's subtitles.ts.
     * - Relay GET /relay/mergesubs + /relay/translate stay for
     *   public files only; Miruro subs must go through /relay/store.
     */
    private class SubCue(
        val startMs: Long,
        val endMs: Long,
        val start: String,
        val end: String,
        val text: String
    )

    private fun subTsToMs(t: String): Long {
        val clean = t.trim().replace(',', '.')
        val parts = clean.split(":")
        val secParts = parts.last().split(".")
        val secs = secParts[0].toLongOrNull() ?: 0L
        val ms = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        var total = secs * 1000 + ms
        if (parts.size == 3) total += parts[0].toLongOrNull()?.times(3600000) ?: 0L
        if (parts.size >= 2) total += parts[parts.size - 2].toLongOrNull()?.times(60000) ?: 0L
        return total
    }

    private fun subFmtMs(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return "%02d:%02d:%02d.%03d".format(h, m, s, r)
    }

    private fun parseSubCues(text: String): List<SubCue> {
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')
        val tsRe = Regex("""(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3})""")
        val out = ArrayList<SubCue>()
        for (block in norm.split(Regex("""\n\s*\n"""))) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT", ignoreCase = true)) continue
            if (lines[0].startsWith("NOTE") || lines[0].startsWith("STYLE") || lines[0].startsWith("REGION")) continue
            val tsIdx = lines.indexOfFirst { tsRe.containsMatchIn(it) }
            if (tsIdx < 0) continue
            val m = tsRe.find(lines[tsIdx]) ?: continue
            val body = cleanSubText(lines.drop(tsIdx + 1).joinToString("\n"))
            if (body.isEmpty()) continue
            val sMs = subTsToMs(m.groupValues[1])
            val eMs = subTsToMs(m.groupValues[2])
            out.add(SubCue(sMs, eMs, subFmtMs(sMs), subFmtMs(eMs), body))
        }
        return out
    }

    private fun buildSubVTT(cues: List<SubCue>, texts: List<String>? = null): String {
        val sb = StringBuilder("WEBVTT\n\n")
        for ((i, c) in cues.withIndex()) {
            sb.append(c.start).append(" --> ").append(c.end).append("\n")
            sb.append(texts?.getOrNull(i) ?: c.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun mergeSubVTT(engText: String, araText: String): String {
        val eng = parseSubCues(engText)
        val ara = parseSubCues(araText)
        val used = HashSet<Int>()
        val merged = ArrayList<SubCue>()
        for (e in eng) {
            val parts = ArrayList<String>()
            for ((i, a) in ara.withIndex()) {
                if (i in used) continue
                if (a.startMs < e.endMs && a.endMs > e.startMs) {
                    used.add(i)
                    parts.add(a.text)
                }
            }
            val text = if (parts.isEmpty()) e.text else parts.joinToString(" ") + "\n" + e.text
            merged.add(SubCue(e.startMs, e.endMs, e.start, e.end, text))
        }
        for ((i, a) in ara.withIndex()) {
            if (i !in used) merged.add(a)
        }
        merged.sortBy { it.startMs }
        return buildSubVTT(merged)
    }

    /** Repairs double-encoded UTF-8 Arabic + strips SRT tags (see subtitles.ts). */
    private val win1252Special = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E,
        '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97,
        '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F
    )

    private fun fixSubEncoding(str: String): String {
        // Per-run repair (same as subtitles.ts): remap each run of
        // Latin-1/Win1252 chars back to bytes and UTF-8-decode it,
        // keeping genuine Arabic/CJK runs untouched. One cue mixes
        // proper Arabic with mojibake, and 0x80-0x9F bytes arrive as
        // Latin-1 control chars (e.g. U+0081) — a whole-string remap
        // bails on those.
        val out = StringBuilder()
        var run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val text = run.toString()
            run = StringBuilder()
            try {
                val bytes = ByteArray(text.length)
                for ((i, c) in text.withIndex()) {
                    val code = c.code
                    bytes[i] = when {
                        code < 0x100 -> code.toByte()
                        win1252Special.containsKey(c) -> win1252Special.getValue(c).toByte()
                        else -> { out.append(text); return }
                    }
                }
                val fixed = String(bytes, Charsets.UTF_8)
                if (fixed.contains('\uFFFD')) out.append(text)
                else if (fixed.any { it in '\u0600'..'\u06FF' }) out.append(fixed)
                else out.append(text)
            } catch (e: Exception) {
                out.append(text)
            }
        }
        for (c in str) {
            if (c.code < 0x100 || win1252Special.containsKey(c)) run.append(c)
            else { flush(); out.append(c) }
        }
        flush()
        return out.toString()
    }

    private fun cleanSubText(t: String): String {
        return fixSubEncoding(
            t.replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
        ).trim()
    }

    /**
     * Hosts a finished VTT on the relay and returns its https URL.
     * The player only lists https subtitle entries, so the merged /
     * translated VTT must live at a URL — the relay stores it in
     * Deno KV (24h) and returns a short /s/<id> link.
     */
    private suspend fun storeSub(vtt: String): String? {
        return try {
            val res = app.post("$relayBase/relay/store", data = mapOf("vtt" to vtt))
            val url = org.json.JSONObject(res.text).optString("url")
            url.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- playback ----------

    /**
     * v2: the FIRST parser (v1) matched only one server shape and
     * missed every other title. Verified 2026-11-05 on 4 live watch
     * pages: server objects vary — extra fields between provider
     * and servers (skipTimes), headers with Origin BEFORE Referer,
     * and embed:null before streams. A per-provider-block regex
     * cannot survive that, so v2 parses GLOBALLY and positionally:
     * every server:"..." + headers + streams url in the page,
     * each attributed to the nearest preceding track:"..." and
     * provider:"..." markers. Result on the same 4 pages: 13-16
     * streams each (v1 found 8 on one title, 0 on the other three).
     * Subtitles are collected globally and deduped by file URL.
     * The DEBUG link now carries its counts in its NAME (not only
     * in the source field), so the on-phone source list itself
     * shows them.
     */
    override suspend fun loadLinks(
        api: MainAPI,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var trackCount = 0
        var providerCount = 0
        var streamCount = 0
        var subCount = 0
        var pageLen = 0
        var engUrl: String? = null
        var engDiag = "eng:-"
        var araDiag = "ara:-"
        var storeDiag = "store:-"
        var trDiag = "tr:-"
        try {
            val html = fetchHtml(data)
            pageLen = html.length
            if (html.isNotEmpty()) {
                val trackMarkers = Regex("""track:"(sub|dub|ssub)"""")
                    .findAll(html).map { it.range.first to it.groupValues[1] }.toList()
                trackCount = trackMarkers.map { it.second }.toSet().size
                val providerMarkers = Regex("""provider:"([^"]+)"""")
                    .findAll(html).map { it.range.first to it.groupValues[1] }.toList()
                providerCount = providerMarkers.map { it.second }.toSet().size
                val seenStreams = HashSet<String>()
                // Servers WITH headers: headers are flat (no nested
                // braces), Referer is extracted from them (Origin
                // may come first). The tempered span between headers
                // and streams never crosses into the next server.
                val serverRegex = Regex(
                    """server:"([^"]+)",headers:\{([^}]*)\},(?:(?!server:").)*?streams:\[\{url:"([^"]+)"""",
                    RegexOption.DOT_MATCHES_ALL
                )
                val noHeadRegex = Regex(
                    """server:"([^"]+)",(?:(?!server:").)*?streams:\[\{url:"([^"]+)"""",
                    RegexOption.DOT_MATCHES_ALL
                )
                val refererRegex = Regex("""Referer:"([^"]*)"""")
                suspend fun emit(pos: Int, server: String, referer: String, streamUrl: String) {
                    if (!seenStreams.add(streamUrl)) return
                    streamCount++
                    val track = trackMarkers.lastOrNull { it.first < pos }?.second ?: "sub"
                    val provider = providerMarkers.lastOrNull { it.first < pos }?.second ?: "Miruro"
                    val trackLabel = if (track == "dub") "مدبلج" else "مترجم"
                    callback(
                        newExtractorLink(
                            "Miruro",
                            "$provider $server [$trackLabel]",
                            streamUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer.ifEmpty { "$mainUrl/" }
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                for (m in serverRegex.findAll(html)) {
                    val referer = refererRegex.find(m.groupValues[2])?.groupValues?.get(1) ?: ""
                    emit(m.range.first, m.groupValues[1], referer, m.groupValues[3])
                }
                for (m in noHeadRegex.findAll(html)) {
                    emit(m.range.first, m.groupValues[1], "", m.groupValues[2])
                }
                val seenSubs = HashSet<String>()
                val subRegex = Regex(
                    """language:"(\w+)",label:"([^"]+)",file:"([^"]+\.vtt)""""
                )
                for (sub in subRegex.findAll(html)) {
                    val file = sub.groupValues[3]
                    if (!seenSubs.add(file)) continue
                    subCount++
                    if (engUrl == null && sub.groupValues[1].lowercase() in setOf("en", "eng")) engUrl = file
                    subtitleCallback(SubtitleFile(sub.groupValues[2], file))
                }
            }
        } catch (e: Exception) {
            // Fall through to the DEBUG marker below.
        }
        // v3+v4: Arabic subtitle from OpenSubtitles, then in-addon
        // merge / phone-IP translation (fail-soft, streams untouched).
        var araUrl: String? = null
        try {
            araUrl = arabicSubFor(data)
            if (AnimeVerseSubs.mode != "en") araUrl?.let { subtitleCallback(SubtitleFile("العربية", it)) }
        } catch (e: Exception) {
        }
        try {
            // v11 (panel verdict 2026-11-05, 6/6): merge on the SERVER.
            // The phone never fetches subtitle files for the merge: it
            // only resolves the OpenSubtitles URLs (public, proven
            // — the direct "العربية" entry already works) and emits the
            // relay's GET merge URL, which returns the merged VTT
            // directly. This removes the three phone-side failure
            // points (site EN fetch, in-addon merge, store POST).
            // v12: send ALL Arabic candidates — the relay scores each
            // against the English file and merges the best-aligned one.
            // v22: emissions gated by the settings gear
            // (AnimeVerseSubs.mode) — "ar" Arabic only, "en" English
            // only, "dual" everything (v21 behaviour).
            val subMode = AnimeVerseSubs.mode
            val engOsUrl = osSubFor(data, "eng")
            val araUrls = osSubsFor(data, "ara")
            engDiag = if (engOsUrl != null) "eng:os" else "eng:-"
            araDiag = "ara:${araUrls.size}"
            if (subMode == "en") {
                engOsUrl?.let { subtitleCallback(SubtitleFile("الإنجليزية", it)) }
            } else if (engOsUrl != null && araUrls.isNotEmpty()) {
                if (subMode == "dual") {
                    val mergeUrl = StringBuilder("$relayBase/relay/mergesubs?eng=")
                        .append(URLEncoder.encode(engOsUrl, "UTF-8"))
                    for (u in araUrls) mergeUrl.append("&ara=").append(URLEncoder.encode(u, "UTF-8"))
                    subtitleCallback(SubtitleFile("عربي + English", mergeUrl.toString()))
                    storeDiag = "merge:ok"
                }
            } else if (engOsUrl != null) {
                // No ready Arabic: phone-IP auto-translate stays
                // phone-side (the relay cannot reach gtx); the
                // merged file is still hosted via the store.
                val engText = fetchSub(engOsUrl)
                engDiag = "eng:${parseSubCues(engText).size}cue(os)"
                if (engText.isNotEmpty()) {
                    val cues = parseSubCues(engText)
                    if (cues.isNotEmpty()) {
                        // v21: Goojara v26 engine (gtx + MyMemory
                        // fallback, cached) — the old private
                        // gtx-only copy threw on the phone.
                        // Miruro's own SubCue is the same shape as
                        // AnimeSubs.SubCue; convert one line.
                        val res = AnimeTranslate.translateCues(
                            cues.map { AnimeSubs.SubCue(it.startMs, it.endMs, it.start, it.end, it.text) },
                            engOsUrl, engText
                        )
                        if (res.arUrl != null) {
                            trDiag = if (AnimeTranslate.mmUsed) "tr:mm" else "tr:ok"
                            subtitleCallback(SubtitleFile("العربية (تلقائية)", res.arUrl))
                            if (subMode == "dual") res.dualUrl?.let { subtitleCallback(SubtitleFile("عربي + English", it)) }
                            storeDiag = if (subMode == "dual" && res.dualUrl != null) "store:ok" else "store:ar-only"
                        } else {
                            trDiag = "tr:fail-${res.failStage ?: "gtx"}"
                            storeDiag = "store:tr-fail"
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        // v9: short DEBUG name (the v8 name was ~100 chars and may
        // have been truncated/hidden) and wrapped so nothing can
        // kill it — if loadLinks runs at all, this MUST appear.
        try {
            callback(
                newExtractorLink(
                    "Miruro",
                    "DEBUG v22 $engDiag $araDiag $storeDiag $trDiag mode:${AnimeVerseSubs.mode}",
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
        }
        return true
    }
}
