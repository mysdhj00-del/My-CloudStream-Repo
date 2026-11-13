package com.reanime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Re:ANIME (reanime.cz) — anime with sub & dub.
 * Copied from raghav ReAnime v9, rewritten: org.json instead of Jackson,
 * no coroutine builders (sequential resolve), no remote domain lookup, no
 * donation prompt, Arabic UI. Plus the EN->AR subtitle gear: every English
 * track the flixcloud embed offers is translated to Arabic with free
 * Google Translate (phone-side gtx) and emitted as EN alone / AR alone /
 * AR+EN dual, with a local cache so repeat plays skip translation.
 */
class ReAnimeProvider : MainAPI() {

    override var mainUrl = "https://reanime.cz"
    override var name = "ReAnime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "ar"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "popular" to "الأكثر رواجًا",
        "latest-aired" to "أحدث الحلقات",
        "new-on-site" to "أُضيف حديثًا",
        "upcoming" to "قادم قريبًا"
    )

    private data class EpisodeRef(
        val slug: String,
        val ep: Int,
        val lang: String,
        val anilistId: Int?,
        val tmdbId: Int?,
        val season: Int?
    ) {
        fun toJson(): String {
            val o = JSONObject()
            o.put("slug", slug)
            o.put("ep", ep)
            o.put("lang", lang)
            if (anilistId != null) o.put("anilistId", anilistId)
            if (tmdbId != null) o.put("tmdbId", tmdbId)
            if (season != null) o.put("season", season)
            return o.toString()
        }

        companion object {
            fun fromJson(raw: String): EpisodeRef? {
                return try {
                    val o = JSONObject(raw)
                    EpisodeRef(
                        o.optString("slug"),
                        o.optInt("ep", 0),
                        o.optString("lang"),
                        if (o.has("anilistId") && !o.isNull("anilistId")) o.optInt("anilistId") else null,
                        if (o.has("tmdbId") && !o.isNull("tmdbId")) o.optInt("tmdbId") else null,
                        if (o.has("season") && !o.isNull("season")) o.optInt("season") else null
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        when (request.data) {
            "popular" -> {
                val items = ReAnimeApi.searchSorted("popularity_desc", page)
                return newHomePageResponse(
                    request.name,
                    items.mapNotNull { it.toSearchResponse() },
                    hasNext = items.isNotEmpty()
                )
            }
            "latest-aired", "new-on-site", "upcoming" -> {
                val (list, hasMore) = ReAnimeApi.homeSection(request.data, page)
                return newHomePageResponse(
                    request.name,
                    list.mapNotNull { it.toSearchResponse() },
                    hasNext = hasMore
                )
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return ReAnimeApi.search(query, 1).mapNotNull { it.toSearchResponse() }
    }

    private fun ReAnimeApi.SearchItem.toSearchResponse(): SearchResponse? {
        val slug = animeId ?: return null
        val displayTitle = title?.display() ?: return null
        val romaji = title?.romaji
        val poster = coverImage?.best()
        val year = seasonYear
        val subCount = subbed ?: 0
        val dubCount = dubbed ?: 0
        val scoreValue = averageScore
        return newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$slug") {
            this.posterUrl = poster
            this.year = year
            this.otherName = romaji
            addDubStatus(dubExist = dubCount > 0, subExist = subCount > 0)
            scoreValue?.let { score = Score.from100(it.toDouble()) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.removePrefix("$mainUrl/").removePrefix("/").removePrefix("anime/")
        val anime = ReAnimeApi.animeDetail(slug) ?: return null
        val eps = ReAnimeApi.episodes(slug)
        val title = anime.title?.display() ?: return null
        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.episodeNumber ?: continue
            if (ep.subbed == true) subEps.add(ep.toEpisode(slug, num, "sub", anime))
            if (ep.dubbed == true) dubEps.add(ep.toEpisode(slug, num, "dub", anime))
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) {
            for (ep in eps) {
                val num = ep.episodeNumber ?: continue
                subEps.add(ep.toEpisode(slug, num, "sub", anime))
            }
        }

        val type = when {
            anime.format == "MOVIE" && dubEps.isNotEmpty() -> TvType.Anime
            anime.format == "MOVIE" -> TvType.AnimeMovie
            anime.format == "OVA" || anime.format == "ONA" || anime.format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", type) {
            this.engName = anime.title?.english
            this.japName = anime.title?.romaji ?: anime.title?.native
            this.posterUrl = anime.coverImage?.best()
            this.backgroundPosterUrl = anime.bannerImage
            this.year = anime.seasonYear
            this.showStatus = showStatus
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres ?: anime.tags ?: emptyList()
            this.duration = anime.duration
            this.score = anime.averageScore?.let { Score.from100(it.toDouble()) }
            addAniListId(anime.anilistId ?: 0)
            addMalId(anime.malId ?: 0)
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun ReAnimeApi.EpisodeEntry.toEpisode(
        slug: String,
        num: Int,
        lang: String,
        anime: ReAnimeApi.AnimeDetail
    ): Episode {
        val ref = EpisodeRef(
            slug = slug,
            ep = num,
            lang = lang,
            anilistId = anime.anilistId,
            tmdbId = anime.themoviedbId,
            season = anime.externalTmdbSeason
        )
        return newEpisode(ref.toJson()) {
            this.name = title?.takeIf { it.isNotBlank() && !it.startsWith("Episode ") }
            this.episode = num
            this.description = description?.takeIf { it.isNotBlank() }?.let(::stripHtml)
            this.posterUrl = thumbnail?.takeIf { it.startsWith("http") }
            parseAirDate(aired)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    // ---------- subtitle gear ----------

    private data class SubTrack(val url: String, val label: String, val format: String, val isDefault: Boolean)

    private val subArrayRe = Regex("""subtitles:\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val subEntryRe = Regex("""\{url:"([^"]+)",language:"([^"]+)",format:"([^"]+)",default:(true|false)\}""")
    private val fileIdRe = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)

    private fun shortArLabel(label: String): String {
        val inner = Regex("""\(([^)]+)\)""").find(label)?.groupValues?.get(1) ?: return "إنجليزي"
        return when {
            inner.contains("Dubtitle", ignoreCase = true) -> "دبلجة AI"
            inner.contains("Dialogue", ignoreCase = true) -> "حوار"
            inner.contains("Signs", ignoreCase = true) -> "إشارات وأغاني"
            inner.contains("Honorific", ignoreCase = true) -> "ألقاب"
            else -> inner
        }
    }

    private data class SubFetch(val tracks: List<SubTrack>, val embOk: Int, val pageCode: Int)

    private suspend fun fetchSubTracks(embedId: String): SubFetch {
        val headers = mapOf(
            "User-Agent" to (ReAnimeApi.BROWSER_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
            "Referer" to "${ReAnimeApi.FLIX_EMBED_BASE}/",
            "Accept" to "*/*"
        )
        var lastCode = 0
        for (v in listOf("1", "2")) {
            try {
                val page = app.get("${ReAnimeApi.FLIX_EMBED_BASE}/e/$embedId?v=$v", headers = headers)
                lastCode = page.code
                if (!page.isSuccessful) continue
                val arr = subArrayRe.find(page.text)?.groupValues?.get(1) ?: continue
                val tracks = subEntryRe.findAll(arr).map {
                    SubTrack(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4] == "true")
                }.toList()
                if (tracks.isNotEmpty()) return SubFetch(tracks, 1, lastCode)
            } catch (_: Exception) {
            }
        }
        return SubFetch(emptyList(), 1, lastCode)
    }

    private suspend fun fetchTrackText(url: String, embedUrl: String): String? {
        return try {
            val headers = mapOf(
                "User-Agent" to (ReAnimeApi.BROWSER_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                "Referer" to embedUrl,
                "Accept" to "*/*"
            )
            val res = app.get(url, headers = headers)
            if (res.isSuccessful) res.text.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val ref = EpisodeRef.fromJson(data) ?: return false
        if (ref.ep <= 0) return false

        val servers = ReAnimeApi.flixServers(ref.anilistId, ref.tmdbId, ref.season, ref.ep)
        if (servers.isEmpty()) return false

        // Every server of an episode (HD-1/HD-2, sub/dub) points at the same flixcloud file:
        // one MKV with both the Japanese and English audio and all subtitles inside
        val embeds = servers.mapNotNull { s ->
            s.dataLink?.takeIf { it.startsWith("http") && FlixDownload.embedId(it) != null }
        }.distinctBy { FlixDownload.embedId(it) }

        var videoLinks = 0
        for (embedUrl in embeds) {
            val file = FlixDownload.resolve(embedUrl) ?: continue
            videoLinks++
            val label = "ReAnime - مترجم ومدبلج" + (file.size?.let { " ($it)" } ?: "")
            callback.invoke(
                newExtractorLink(label, label, file.url, type = ExtractorLinkType.VIDEO) {
                    file.quality?.let { this.quality = it }
                    this.headers = file.headers
                }
            )
        }

        // ---- subtitle gear: every English track -> EN alone / AR alone / AR+EN dual ----
        var enTotal = 0
        var arCount = 0
        var subEmb = 0
        var subPg = 0
        var subTr = 0
        val failLabels = ArrayList<String>()
        ReAnimeSubs.gtx429 = false
        val subEmbedId = servers.firstOrNull { it.hardsub != true }
            ?.dataLink?.let { FlixDownload.embedId(it) }
        if (subEmbedId != null) {
            subEmb = 1
            val fetched = fetchSubTracks(subEmbedId)
            subPg = fetched.pageCode
            subTr = fetched.tracks.size
            val tracks = fetched.tracks
            val enTracks = tracks.filter { it.label.startsWith("English", ignoreCase = true) }
            enTotal = enTracks.size
            val embedUrl = "${ReAnimeApi.FLIX_EMBED_BASE}/e/$subEmbedId?v=1"
            for (track in enTracks) {
                val short = shortArLabel(track.label)
                val fileId = fileIdRe.find(track.url)?.value ?: track.url.hashCode().toString()
                val fileName = track.url.substringAfterLast("/")
                val cacheKey = "$fileId|$fileName"
                val cachedAr = ReAnimeSubs.cachedAr(cacheKey)
                val cachedDual = ReAnimeSubs.cachedDual(cacheKey)
                if (cachedAr != null) {
                    subtitleCallback(SubtitleFile(track.label, track.url))
                    subtitleCallback(SubtitleFile("العربية ($short)", cachedAr))
                    if (cachedDual != null) {
                        subtitleCallback(SubtitleFile("العربية + الإنجليزية ($short)", cachedDual))
                    }
                    arCount++
                    continue
                }
                val raw = fetchTrackText(track.url, embedUrl)
                val cues = when {
                    raw == null -> emptyList()
                    track.format.equals("ass", ignoreCase = true) -> ReAnimeSubs.parseAssCues(raw)
                    else -> ReAnimeSubs.parseSubCues(raw)
                }
                if (cues.isEmpty()) {
                    failLabels.add(short)
                    subtitleCallback(SubtitleFile(track.label, track.url))
                    continue
                }
                val translated = ReAnimeSubs.translateSubCues(cues)
                if (translated == null) {
                    failLabels.add(short)
                    subtitleCallback(SubtitleFile(track.label, track.url))
                    continue
                }
                val arVtt = ReAnimeSubs.buildSubVTT(cues, translated)
                val arUrl = ReAnimeSubs.storeSub(arVtt)
                if (arUrl == null) {
                    failLabels.add(short)
                    subtitleCallback(SubtitleFile(track.label, track.url))
                    continue
                }
                val dualVtt = ReAnimeSubs.buildDualVTT(cues, translated)
                val dualUrl = ReAnimeSubs.storeSub(dualVtt)
                ReAnimeSubs.putCache(cacheKey, arUrl, dualUrl)
                subtitleCallback(SubtitleFile(track.label, track.url))
                subtitleCallback(SubtitleFile("العربية ($short)", arUrl))
                if (dualUrl != null) {
                    subtitleCallback(SubtitleFile("العربية + الإنجليزية ($short)", dualUrl))
                }
                arCount++
            }
        }

        val secs = (System.currentTimeMillis() - t0) / 1000
        val debugName = "DEBUG ReAnime v2 | srv=${servers.size} | ext=$videoLinks | sub:emb=$subEmb | sub:pg=$subPg | sub:tr=$subTr | sub:ar=$arCount/$enTotal" +
            (if (failLabels.isNotEmpty()) " | sub:fail=${failLabels.joinToString(",")}" else "") +
            (if (ReAnimeSubs.gtx429) " | sub:gtx429" else "") +
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
        return videoLinks > 0
    }
}
