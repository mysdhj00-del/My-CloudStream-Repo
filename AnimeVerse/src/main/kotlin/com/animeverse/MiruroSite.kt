package com.animeverse

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
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

/**
 * Miruro.tv — SvelteKit anime site, first AnimeVerse member.
 *
 * Chain traced live 2026-11-05 (no /api calls: the site's own
 * /api routes answer Cloudflare "Attention Required!" from
 * datacenter IPs, while the plain HTML pages answer 200):
 * - Home row: GET https://miruro.tv/trending — SSR cards inside
 *   <article> blocks: <a href="/info/<id>/<slug>"
 *   aria-label="Title"> + sibling <img src="poster">.
 * - Search: GET https://miruro.tv/search?q=<query> — SSR cards in
 *   .browse-grid: <a href="/info/..."><img src alt="Title">.
 *   (The client-side route /api/search/browse?q=&limit=8&type=ANIME
 *   exists in the JS bundles but is CF-gated from the sandbox.)
 * - Detail: GET https://miruro.tv/info/<id>/<slug> — JSON-LD
 *   (@type TVSeries vs Movie, name, description, genre) + an
 *   embedded "episodes:<N>" count. Episodes are watch links
 *   /watch/<id>/<slug>?ep=N (verified ?ep=1 pattern).
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
    override val sections = listOf(
        "miruro:trending" to "الرائج"
    )

    private const val mainUrl = "https://miruro.tv"
    private val cloudflareKiller by lazy { CloudflareKiller() }

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

    // ---------- cards ----------

    private val articleRegex = Regex(
        """<article[^>]*>(.*?)</article>""",
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

    override suspend fun homeSection(api: MainAPI, data: String): List<SearchResponse> {
        if (data != "miruro:trending") return emptyList()
        return parseCards(api, fetchHtml("$mainUrl/trending"))
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
        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore(" - Miruro")?.trim()?.ifEmpty { null }
            ?: jsonString(html, "name")
            ?: url.substringAfterLast('/').replace('-', ' ')
        val cover = Regex(
            """https://s4\.anilist\.co/file/anilistcdn/media/anime/cover/large/[^"\\]+"""
        ).find(html)?.value
        val poster = jsonString(html, "image")?.takeIf { it.contains("anilistcdn/media/anime/cover") }
            ?: cover
        val plot = jsonString(html, "description")
        val isMovie = html.contains("\"@type\":\"Movie\"")
        val watchUrl = url.replace("/info/", "/watch/")
        if (isMovie) {
            return api.newMovieLoadResponse(cleanTitle(title), url, TvType.AnimeMovie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        val epCount = Regex("""episodes:(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episodes = (1..epCount).map { n ->
            api.newEpisode("$watchUrl?ep=$n") {
                this.name = "الحلقة $n"
                this.episode = n
            }
        }
        return api.newTvSeriesLoadResponse(cleanTitle(title), url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
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

    private suspend fun arabicSubFor(data: String): String? {
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
                var firstAra: String? = null
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("lang") != "ara") continue
                    val u = o.optString("url")
                    if (u.isBlank()) continue
                    if (firstAra == null) firstAra = u
                    if (o.optString("SubEncoding") == "UTF-8") return u
                }
                if (firstAra != null) return firstAra
            }
            null
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
                    subtitleCallback(SubtitleFile(sub.groupValues[2], file))
                }
            }
        } catch (e: Exception) {
            // Fall through to the DEBUG marker below.
        }
        // v3: Arabic subtitle from OpenSubtitles (fail-soft).
        try {
            arabicSubFor(data)?.let { subtitleCallback(SubtitleFile("العربية", it)) }
        } catch (e: Exception) {
        }
        callback(
            newExtractorLink(
                "Miruro",
                "DEBUG v1 | tracks=$trackCount prov=$providerCount streams=$streamCount subs=$subCount len=$pageLen | Mux test",
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
