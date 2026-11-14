package com.animegg

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * AnimeGG (animegg.org) — anime subbed & dubbed.
 * Chain traced live 2026-11-14 (see ~/workspace/animegg-study/NOTES.md):
 *   home/popular/search -> /series/<slug> -> episode list
 *   -> episode page ul#videos a[data-id] -> /embed/<id>
 *   -> JWPlayer videoSources /play/<id>/video.mp4?for=<token>
 *   -> 302 -> vidcache mp4 (HTTP 206 verified).
 * Single mirror class ("Animegg"), versions subbed and/or dubbed.
 * The site serves NO subtitle tracks at all, so every subtitle
 * comes from the gear: OpenSubtitles (IMDb suggest, no key) for
 * ready AR/EN, else phone-side AI translation EN->AR (free gtx
 * first, MyMemory fallback — gtx is carrier-blocked on the
 * user's phone, Panel #10, so the fallback is essential).
 * Trailers follow the v3 rule: Latin titles only, Arabic-only
 * titles get the poster only, no YouTube query at all.
 */
class AnimeGGProvider : MainAPI() {

    override var mainUrl = "https://www.animegg.org"
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

    /** Pager state per section: the site's own "Next" link wins
     * over computed offsets — its page sizes are irregular
     * (25, then 55, then 130 cards) but the sets are disjoint. */
    private val nextBySection = HashMap<String, String>()
    private val seenBySection = HashMap<String, MutableSet<String>>()

    private fun seenSet(key: String): MutableSet<String> =
        seenBySection.getOrPut(key) { HashSet() }

    private fun fixThumb(u: String?): String? {
        if (u.isNullOrBlank()) return null
        var t = u.trim()
        if (t.startsWith("//")) t = "https:$t"
        t = t.replace("cdn.oose.io", "vidcache.net:8161")
        return t
    }

    private fun absUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim()
        if (h.startsWith("http")) return h
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("/")) return mainUrl + h
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

    // ---------- home ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        return when (request.data) {
            "latest" -> {
                if (page != 1) return newHomePageResponse(request.name, emptyList(), false)
                val items = latestFeed()
                newHomePageResponse(request.name, items, false)
            }
            "new" -> {
                if (page != 1) return newHomePageResponse(request.name, emptyList(), false)
                val doc = try {
                    app.get("$mainUrl/new-series", headers = browserHeaders, referer = mainUrl).document
                } catch (_: Exception) {
                    return newHomePageResponse(request.name, emptyList(), false)
                }
                val seen = seenSet("new")
                val out = ArrayList<SearchResponse>()
                for (el in doc.select("div.entry")) {
                    val a = el.selectFirst("a[href^=/series/]") ?: continue
                    val url = absUrl(a.attr("href")) ?: continue
                    if (!seen.add(url)) continue
                    val title = el.selectFirst("div.entry-title a")?.text()?.trim()
                        ?: a.attr("title").trim()
                    if (title.isBlank()) continue
                    val poster = fixThumb(el.selectFirst("img")?.attr("src"))
                    out.add(newAnimeSearchResponse(title, url) { this.posterUrl = poster })
                }
                newHomePageResponse(request.name, out, false)
            }
            "popular", "ongoing", "completed" -> {
                val key = request.data
                val base = when (key) {
                    "popular" -> "$mainUrl/popular-series?sortBy=hits&sortDirection=DESC&ongoing&limit=25&start=0"
                    "ongoing" -> "$mainUrl/popular-series?ongoing=true"
                    else -> "$mainUrl/popular-series?ongoing=false"
                }
                val url = if (page == 1) base
                else nextBySection["$key|$page"]
                    ?: "$mainUrl/popular-series?sortBy=hits&sortDirection=DESC&ongoing&limit=25&start=${(page - 1) * 25}"
                val doc = try {
                    app.get(url, headers = browserHeaders, referer = mainUrl).document
                } catch (_: Exception) {
                    return newHomePageResponse(request.name, emptyList(), false)
                }
                val seen = seenSet(key)
                val out = ArrayList<SearchResponse>()
                for (el in doc.select("li.fea")) {
                    val titleA = el.selectFirst("div.rightpop > a") ?: continue
                    val curl = absUrl(titleA.attr("href")) ?: continue
                    if (!seen.add(curl)) continue
                    val title = titleA.text().trim()
                    if (title.isBlank()) continue
                    val poster = fixThumb(el.selectFirst("div.img img")?.attr("src"))
                    out.add(newAnimeSearchResponse(title, curl) { this.posterUrl = poster })
                }
                // Follow the site's own pager for the next page.
                val next = doc.select("a[href*=start=]")
                    .firstOrNull { it.text().trim() == "Next" }
                    ?.attr("href")?.let { absUrl(it.replace("&amp;", "&")) }
                if (next != null) nextBySection["$key|${page + 1}"] = next
                newHomePageResponse(request.name, out, out.isNotEmpty() && next != null)
            }
            else -> null
        }
    }

    /** POST /index/recentReleases: 40 latest episode releases as
     * JSON. Cards point at the SERIES (deduped by show) so one
     * poster per series opens the full episode list. */
    private suspend fun latestFeed(): List<SearchResponse> {
        return try {
            val jsonType = "application/json".toMediaType()
            val res = app.post(
                "$mainUrl/index/recentReleases",
                headers = browserHeaders,
                referer = mainUrl,
                requestBody = "{}".toRequestBody(jsonType)
            )
            if (!res.isSuccessful) return emptyList()
            val arr = JSONObject(res.text).optJSONArray("recentReleases") ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<SearchResponse>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val show = o.optJSONObject("Show") ?: continue
                val uri = show.optString("uri")
                if (uri.isBlank()) continue
                val url = "$mainUrl/series/$uri"
                if (!seen.add(url)) continue
                val title = show.optString("title").trim()
                if (title.isBlank()) continue
                val poster = fixThumb(show.optString("thumbnailUrl"))
                out.add(newAnimeSearchResponse(title, url) { this.posterUrl = poster })
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = try {
            app.get(
                "$mainUrl/search/?q=${URLEncoder.encode(query, "UTF-8")}",
                headers = browserHeaders,
                referer = mainUrl
            ).document
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("a.mse[href^=/series/]")) {
            val url = absUrl(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            val title = a.selectFirst("h2")?.text()?.trim() ?: continue
            if (title.isBlank()) continue
            val poster = fixThumb(a.selectFirst("img.media-object")?.attr("src"))
            out.add(newAnimeSearchResponse(title, url) { this.posterUrl = poster })
        }
        return out
    }

    // ---------- detail ----------

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = browserHeaders, referer = mainUrl).document
        } catch (_: Exception) {
            return null
        }
        val title = (doc.selectFirst("div.media h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().removePrefix("Watch ").removeSuffix(" Episodes").trim())
        if (title.isBlank()) return null
        val poster = fixThumb(doc.selectFirst("div.media img.media-object")?.attr("src"))
        val infoText = doc.select("p.infoami").eachText().joinToString(" ")
        val epCount = Regex("""Episodes:\s*(\d+)""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
        val status = when {
            infoText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            infoText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }
        val plot = doc.wholeText().substringAfter("Plot Summary:", "").trim()
            .takeIf { it.isNotBlank() }?.substringBefore("\n")?.trim()
        val tags = doc.select("a[href^=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = Regex("""(19|20)\d\d""").find(title)?.value?.toIntOrNull()

        val subEps = ArrayList<Episode>()
        val dubEps = ArrayList<Episode>()
        for (li in doc.select("ul.newmanga li")) {
            val a = li.selectFirst("a.anm_det_pop[href]") ?: continue
            val epUrl = absUrl(a.attr("href")) ?: continue
            val num = Regex("""-episode-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""Episode\s+(\d+)""").find(li.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val hasSub = li.select("a[href\$=#subbed]").isNotEmpty()
            val hasDub = li.select("a[href\$=#dubbed]").isNotEmpty()
            val epName = "الحلقة $num"
            if (hasSub || (!hasSub && !hasDub)) {
                subEps.add(
                    newEpisode(EpisodeRef(epUrl, num, title, "sub").toJson()) {
                        this.name = epName
                        this.episode = num
                    }
                )
            }
            if (hasDub) {
                dubEps.add(
                    newEpisode(EpisodeRef(epUrl, num, title, "dub").toJson()) {
                        this.name = epName
                        this.episode = num
                    }
                )
            }
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) return null

        val type = when {
            title.contains("(OVA)", ignoreCase = true) -> TvType.OVA
            (epCount ?: subEps.size) <= 1 -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.year = year
            this.showStatus = status
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
            this.trailers = AnimeGGSubs.trailerFor(title, isSeries = type != TvType.AnimeMovie, acceptPv = true)
        }
    }

    // ---------- playback ----------

    private val videoSourcesRe =
        Regex("""\{file:\s*"([^"]+)",\s*label:\s*"([^"]+)",\s*bk:\s*"([^"]*)"""")

    private fun decodeBk(bk: String): String? {
        return try {
            if (bk.isBlank()) return null
            val raw = android.util.Base64.decode(bk, android.util.Base64.DEFAULT)
            URLDecoder.decode(String(raw, Charsets.UTF_8), "UTF-8").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun qualityOf(label: String): Int {
        return when (Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()) {
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private data class SubResult(val os: String, val tr: String)

    /**
     * Subtitle gear: OpenSubtitles by title (IMDb suggest, no key).
     * Ready Arabic -> emit it (dual mode merges it with English
     * via the relay). No ready Arabic -> translate the English
     * file to Arabic on the phone (gtx first, MyMemory fallback)
     * and emit AR (+ dual). English-only mode never translates.
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

        if (ara != null) {
            subtitleCallback(SubtitleFile("العربية", ara))
            if (mode == "dual") {
                if (eng != null) {
                    subtitleCallback(SubtitleFile("English", eng))
                    val mergeUrl = "${AnimeGGSubs.relayBase}/relay/mergesubs?eng=" +
                        URLEncoder.encode(eng, "UTF-8") +
                        "&ara=" + URLEncoder.encode(ara, "UTF-8")
                    subtitleCallback(SubtitleFile("العربية + الإنجليزية", mergeUrl))
                    return SubResult("ar+dual", "-")
                }
            }
            return SubResult("ar", "-")
        }

        if (eng != null) {
            val raw = try {
                val res = app.get(eng)
                if (res.isSuccessful) res.text.takeIf { it.isNotBlank() } else null
            } catch (_: Exception) {
                null
            }
            if (raw.isNullOrBlank()) return SubResult("no-cues", "-")
            val cues = AnimeGGSubs.parseSubCues(raw)
            if (cues.isEmpty()) return SubResult("no-cues", "-")
            val res = AnimeGGTranslate.translateCues(cues, eng, raw)
            if (res.arUrl == null) {
                val stage = res.failStage ?: "gtx"
                return SubResult("tr-fail", stage)
            }
            subtitleCallback(SubtitleFile("العربية", res.arUrl))
            if (mode == "dual") {
                subtitleCallback(SubtitleFile("English", eng))
                if (res.dualUrl != null) {
                    subtitleCallback(SubtitleFile("العربية + الإنجليزية", res.dualUrl))
                }
            }
            val trMark = if (AnimeGGTranslate.mmUsed) "tr-mm" else "tr-ok"
            return SubResult("tr", trMark)
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
        val ref = EpisodeRef.fromJson(data) ?: return false
        if (ref.ep <= 0 || ref.u.isBlank()) return false
        val mode = AnimeGGGear.mode
        val wantLang = if (ref.l == "dub") "dubbed" else "subbed"
        val langLabel = if (ref.l == "dub") "مدبلج" else "مترجم"

        val epDoc = try {
            app.get(ref.u, headers = browserHeaders, referer = mainUrl).document
        } catch (e: Exception) {
            return false
        }
        if (epDoc.select("img[src*=animegg-unavailable]").isNotEmpty()) return false

        val tabs = epDoc.select("ul#videos a[data-id]")
        val wanted = tabs.filter { it.attr("data-version") == wantLang }
        val useTabs = if (wanted.isNotEmpty()) wanted else tabs
        val tabCount = useTabs.size

        var videoLinks = 0
        var bkLinks = 0
        var embOk = 0
        var embCode = 0
        for (tab in useTabs) {
            val id = tab.attr("data-id").trim()
            if (id.isBlank()) continue
            val embedUrl = "$mainUrl/embed/$id"
            val emb = try {
                val res = app.get(embedUrl, headers = browserHeaders, referer = ref.u)
                embCode = res.code
                if (!res.isSuccessful) continue
                embOk++
                res.text
            } catch (_: Exception) {
                continue
            }
            val seenFiles = HashSet<String>()
            for (m in videoSourcesRe.findAll(emb)) {
                val file = m.groupValues[1]
                val label = m.groupValues[2]
                val bk = decodeBk(m.groupValues[3])
                val playUrl = if (file.startsWith("http")) file else mainUrl + file
                if (seenFiles.add(playUrl)) {
                    videoLinks++
                    val linkName = "AnimeGG $langLabel $label"
                    callback(
                        newExtractorLink(
                            "AnimeGG",
                            linkName,
                            playUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityOf(label)
                            this.referer = embedUrl
                            this.headers = browserHeaders
                        }
                    )
                }
                if (bk != null && seenFiles.add(bk)) {
                    bkLinks++
                    val linkName = "AnimeGG $langLabel $label (احتياطي)"
                    callback(
                        newExtractorLink(
                            "AnimeGG",
                            linkName,
                            bk,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityOf(label)
                            this.referer = embedUrl
                            this.headers = browserHeaders
                        }
                    )
                }
            }
        }

        // ---- subtitle gear (mode: ar | en | dual, default dual) ----
        AnimeGGTranslate.gtx429 = false
        val subRes = if (videoLinks > 0 || bkLinks > 0) runOsFlow(ref, subtitleCallback)
        else SubResult("-", "-")

        val secs = (System.currentTimeMillis() - t0) / 1000
        val debugName = "DEBUG AnimeGG v1 | mode=$mode | tabs=$tabCount | emb=$embOk/$embCode | vid=$videoLinks | bk=$bkLinks" +
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
        return videoLinks > 0 || bkLinks > 0
    }
}
