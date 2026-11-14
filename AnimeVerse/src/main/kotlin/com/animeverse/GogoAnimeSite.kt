package com.animeverse

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * GogoAnime (gogoanime.fi) — second AnimeVerse member (v18).
 *
 * NOTE: this is NOT the classic gogoanime3.net (parked/dead,
 * ww19 403). The live site is a WordPress "dramastream" skin
 * on gogoanime.fi (same skin on gogoanime.pe). Plain requests
 * answer 200 from datacenter IPs — no Cloudflare challenge
 * seen from the sandbox; CloudflareKiller stays as fallback.
 *
 * Chain traced live 2026-11-05:
 * - Search: GET /?s=<query> — <div class="listupd"> bsx cards
 *   <a href="/series/<slug>/" title="Title"><img poster>.
 *   Verified: "gachiakuta" -> exactly 1 card. (The /series/
 *   links found elsewhere on the page are sidebar/top lists,
 *   NOT search results — parse only the listupd block.)
 * - Detail: GET /series/<slug>/ — poster (.thumb img), title
 *   (h2), status/genres (.spe / .genxed), plot (.entry-content
 *   p), episodes in .eplister <li><a href=ep><div
 *   class="epl-num">N</div>, paginated ?ep_page=N (13/page,
 *   load() caps at 8 pages / ~104 episodes, newest first).
 *   Dub series are separate pages (slug ends "-dub", episode
 *   URLs have no "-english-subbed" suffix).
 * - Episode: /<slug>-episode-N-english-subbed/ (sub) or
 *   /<slug>-episode-N/ (dub) — player iframe in #pembed.
 *   load() accepts episode URLs too: it follows the
 *   "All Episodes" link back to the series page.
 * - Playback hosts (sampled 7 episodes, 4 classes):
 *   1. Blogger (3/7: One Piece 1179+1180, Apothecary S3E1):
 *      iframe blogger.com/video.g?token=... -> fetch it,
 *      unicode-unescape, take "itag":22/18 "url" videoplayback
 *      links. VERIFIED: GET range -> 206 video/mp4, ftyp mp42.
 *   2. MegaPlay (2/7: Gachiakuta E13, Kaiju No.8 S2E1):
 *      iframe megaplay.buzz/stream/s-2/<realid>/sub (needs
 *      Referer gogoanime.fi; no Referer -> 410 "deleted") ->
 *      data-id from the player div -> GET
 *      /stream/getSources?id=<data-id>&s=bcdn (Referer +
 *      X-Requested-With) -> JSON {tracks:[EN VTT], enc} ->
 *      enc is base64url AES-256-CBC, key "i?LMTAx0Q6,:}50U"
 *      zero-padded to 32 bytes, IV "W0;27ToaUpl_P%'c"
 *      (both defaults visible in lib/newclient.min.js v4.22)
 *      -> {"file": master.m3u8}. VERIFIED on both titles:
 *      master 200, 1080p/720p/360p variants. EN VTT host
 *      (mysubsplay.com) is CF-403 from datacenter IPs — the
 *      player on the phone may still fetch it; the Arabic
 *      layer below does not depend on it.
 *   3. MegaUp (1/7: Solo Leveling S2 Dub E1): megaup.cc/e/...
 *      -> 522 from the sandbox twice; best-effort extraction
 *      (m3u8/mp4/"file") + DEBUG name if it fails.
 *   4. gogoanime.org.es/streaming.php (1/7: Dragon Ball Daima
 *      E1): connection fails from the sandbox; best-effort +
 *      DEBUG name if it fails.
 * - Subtitles: no site subs on Blogger episodes; MegaPlay
 *   ships an English VTT (tracks[0]). The Arabic layer is the
 *   shared AnimeSubs helper (OpenSubtitles via IMDb suggest,
 *   relay GET mergesubs for AR+EN, phone-IP gtx translate
 *   fallback) — same flow as Miruro v11/v12, but through
 *   AnimeSubs (first consumer; Miruro still has its own copies).
 *   Season for the OS lookup is parsed from the series title
 *   ("Season N", default 1) because the site splits seasons
 *   into separate series pages.
 * - Home (standing rule: ALL sections, "<section> — GogoAnime",
 *   full sideways contents):
 *   1. «أحدث الحلقات» — home "Latest Release" bsx cards whose
 *      href is an episode URL; card URL is the derived series
 *      URL (slug before "-episode-"), named "Title — الحلقة N".
 *   2. «توصيات» — home "Recommendation" genre tabs (Adult
 *      Cast, Crossdressing, Gourmet, ...) each holding episode
 *      cards; same series-derivation as the latest row.
 *   3. «الأعلى» — sidebar serieslist <a class="series"> rows.
 *   4. «كل الأنمي» — /series/?status=&type=&order=update,
 *      pages 1..5 (30 cards/page).
 * - DEBUG markers in v18 (standing rule): loadLinks always
 *   emits one DEBUG Mux source carrying host + per-host
 *   outcome + subtitle diagnostics, so an empty on-phone
 *   source list means loadLinks never ran. v20 adds the
 *   step-level diagnostics first built for Aniwatch v19
 *   (eng fetch cue count + translate result), after the
 *   Overgeared and Kurumi-chan reports both showed a silent
 *   "store:-" with a healthy English file — the next
 *   screenshot now names the failing step.
 * - No coroutine imports anywhere (CI classpath law).
 */
object GogoAnimeSite : AnimeSite {
    override val host = "gogoanime.fi"
    // Standing rule (user, 2026-11-05): every member publishes ALL
    // its sections, each titled "<section> — <Site>", with the
    // section's FULL contents sideways-scrolling — never a
    // truncated row, never a bare section name.
    override val sections = listOf(
        "gogo:latest" to "أحدث الحلقات — GogoAnime",
        "gogo:recommended" to "توصيات — GogoAnime",
        "gogo:top" to "الأعلى — GogoAnime",
        "gogo:catalog" to "كل الأنمي — GogoAnime"
    )

    private const val mainUrl = "https://gogoanime.fi"
    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun posterHeaders(): Map<String, String> {
        val out = HashMap<String, String>()
        out["User-Agent"] = headers["User-Agent"] ?: "Mozilla/5.0"
        out["Referer"] = "$mainUrl/"
        return out
    }

    /** Plain request first, CloudflareKiller as fallback. */
    private suspend fun fetchHtml(url: String, referer: String? = null): String {
        val h = if (referer != null) headers + ("Referer" to referer) else headers
        return try {
            app.get(url, headers = h).text
        } catch (e: Exception) {
            try {
                app.get(url, headers = h, interceptor = cloudflareKiller).text
            } catch (e2: Exception) {
                ""
            }
        }
    }

    private fun decodeEntities(s: String): String {
        return s.replace("&#8217;", "'").replace("&#8216;", "'")
            .replace("&#8220;", "\"").replace("&#8221;", "\"")
            .replace("&#8211;", "-").replace("&#8212;", "-")
            .replace("&#39;", "'").replace("&quot;", "\"")
            .replace("&amp;", "&").replace("&#039;", "'")
            .replace("&nbsp;", " ").trim()
    }

    /** Local URL fixer: fixUrl is a MainAPI extension (see
     * AnimeSite.kt note) and this object has no MainAPI receiver
     * in its helpers — all site URLs are absolute anyway. */
    private fun fix(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    private fun seriesUrlFromEpisode(epUrl: String): String? {
        val slug = epUrl.trimEnd('/').substringAfterLast("/")
            .substringBefore("-episode-")
        if (slug.isEmpty() || slug == epUrl) return null
        return "$mainUrl/series/$slug/"
    }

    private fun epNumFromUrl(url: String): Int? {
        return Regex("""-episode-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** One bsx card: (url, title, poster, sub/dub badge). */
    private fun parseBsxCards(html: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val cardRe = Regex(
            """<article class="bs"[^>]*>.*?<a href="([^"]+)"[^>]*title="([^"]*)"[^>]*>.*?<img[^>]*src="([^"]+)"""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in cardRe.findAll(html)) {
            val url = fix(m.groupValues[1])
            val title = decodeEntities(m.groupValues[2])
            val poster = fix(m.groupValues[3])
            if (url.isEmpty() || title.isEmpty()) continue
            out.add(Triple(url, title, poster))
        }
        return out
    }

    private fun toSeriesCard(api: MainAPI, url: String, title: String, poster: String): SearchResponse {
        return api.newTvSeriesSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = posterHeaders()
        }
    }

    override suspend fun homeSection(api: MainAPI, data: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        try {
            when (data) {
                "gogo:latest" -> {
                    val html = fetchHtml("$mainUrl/")
                    // "Latest Release" block only — the Recommendation
                    // block below it also holds episode cards.
                    val block = html.substringAfter("Latest Release", html)
                        .substringBefore("Recommendation", html)
                    for ((url, title, poster) in parseBsxCards(block)) {
                        if (!url.contains("-episode-")) continue
                        val seriesUrl = seriesUrlFromEpisode(url) ?: continue
                        if (!seen.add(seriesUrl)) continue
                        val ep = epNumFromUrl(url)
                        val base = decodeEntities(title.substringBefore(" Episode ").ifEmpty { title })
                        val name = if (ep != null) "$base — الحلقة $ep" else base
                        out.add(toSeriesCard(api, seriesUrl, name, poster))
                    }
                }
                "gogo:recommended" -> {
                    val html = fetchHtml("$mainUrl/")
                    // "Recommendation" is genre tabs (Adult Cast,
                    // Crossdressing, Gourmet, ...) each holding
                    // episode cards — same treatment as latest.
                    val block = html.substringAfter("Recommendation", html)
                        .substringBefore("<div id=\"sidebar\">", html)
                    for ((url, title, poster) in parseBsxCards(block)) {
                        if (!url.contains("-episode-")) continue
                        val seriesUrl = seriesUrlFromEpisode(url) ?: continue
                        if (!seen.add(seriesUrl)) continue
                        val ep = epNumFromUrl(url)
                        val base = decodeEntities(title.substringBefore(" Episode ").ifEmpty { title })
                        val name = if (ep != null) "$base — الحلقة $ep" else base
                        out.add(toSeriesCard(api, seriesUrl, name, poster))
                    }
                }
                "gogo:top" -> {
                    val html = fetchHtml("$mainUrl/")
                    val anchorRe = Regex(
                        """<a class="series" href="([^"]+)"[^>]*>(.*?)</a>""",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    for (m in anchorRe.findAll(html)) {
                        val url = fix(m.groupValues[1])
                        val inner = m.groupValues[2]
                        val poster = Regex("""<img[^>]*src="([^"]+)"""").find(inner)?.groupValues?.get(1) ?: ""
                        val title = Regex("""title="([^"]+)"""").find(inner)?.groupValues?.get(1)
                            ?: Regex("""<h4[^>]*>([^<]+)</h4>""").find(inner)?.groupValues?.get(1)
                            ?: ""
                        if (url.isEmpty() || !seen.add(url)) continue
                        out.add(toSeriesCard(api, url, decodeEntities(title), fix(poster)))
                    }
                }
                "gogo:catalog" -> {
                    for (page in 1..5) {
                        val url = if (page == 1) "$mainUrl/series/?status=&type=&order=update"
                        else "$mainUrl/series/page/$page/?status=&type=&order=update"
                        val html = fetchHtml(url)
                        var added = 0
                        for ((u, title, poster) in parseBsxCards(html)) {
                            if (!u.contains("/series/")) continue
                            if (!seen.add(u)) continue
                            out.add(toSeriesCard(api, u, title, poster))
                            added++
                        }
                        if (added == 0) break
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    override suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = fetchHtml("$mainUrl/?s=$q")
            // Only the listupd block holds search results; the
            // sidebar/top lists repeat /series/ links.
            val block = Regex("""<div class="listupd">(.*?)<div id="sidebar">""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: html
            val seen = HashSet<String>()
            for ((url, title, poster) in parseBsxCards(block)) {
                if (!url.contains("/series/")) continue
                if (!seen.add(url)) continue
                out.add(toSeriesCard(api, url, title, poster))
            }
        } catch (e: Exception) {
        }
        return out
    }

    override suspend fun load(api: MainAPI, url: String): LoadResponse {
        var seriesUrl = url
        if (url.contains("-episode-")) {
            // Episode URL -> follow "All Episodes" back to the series.
            val epHtml = fetchHtml(url)
            val allEp = Regex("""aria-label='All Episodes'[^>]*href='([^']+)'""").find(epHtml)
                ?: Regex("""aria-label="All Episodes"[^>]*href="([^"]+)"""").find(epHtml)
            val found = allEp?.groupValues?.get(1)
            if (!found.isNullOrEmpty()) seriesUrl = fix(found)
            else seriesUrl = seriesUrlFromEpisode(url) ?: url
        }
        val html = fetchHtml(seriesUrl)
        val title = Regex("""<div class="infolimit">\s*<h2>([^<]+)</h2>""").find(html)?.groupValues?.get(1)
            ?.let { decodeEntities(it) }
            ?: Regex("""<title>([^<]+) at Gogoanime</title>""").find(html)?.groupValues?.get(1)
                ?.let { decodeEntities(it) }
            ?: seriesUrl.substringAfter("/series/").trim('/').replace('-', ' ')
        val poster = Regex("""<div class="thumb">\s*<img[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.let { fix(it) }
        val plot = Regex("""<div class="entry-content"[^>]*>\s*<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { decodeEntities(it.replace(Regex("<[^>]+>"), "")) }
        val status = Regex("""<b>Status:</b>\s*([^<]+)""").find(html)?.groupValues?.get(1)?.trim()
        val tags = Regex("""<div class="genxed">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { block ->
                Regex("""<a[^>]*>([^<]+)</a>""").findAll(block).map { decodeEntities(it.groupValues[1]) }.toList()
            } ?: emptyList()
        val isDub = seriesUrl.contains("-dub") || tags.any { it.equals("Dub", ignoreCase = true) }

        val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
        val seenEp = HashSet<String>()
        for (page in 1..8) {
            val pageUrl = if (page == 1) seriesUrl else "$seriesUrl?ep_page=$page"
            val pageHtml = fetchHtml(pageUrl)
            var added = 0
            val epRe = Regex(
                """<li[^>]*>\s*<a href="([^"]+)">\s*<div class="epl-num">(\d+)</div>""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (m in epRe.findAll(pageHtml)) {
                val epUrl = fix(m.groupValues[1])
                val num = m.groupValues[2].toIntOrNull() ?: continue
                if (!seenEp.add(epUrl)) continue
                episodes.add(
                    api.newEpisode(epUrl) {
                        this.name = "الحلقة $num" + if (isDub) " (مدبلج)" else ""
                        this.episode = num
                    }
                )
                added++
            }
            if (added == 0) break
        }
        episodes.sortBy { it.episode }

        return if (episodes.size <= 1 && episodes.isNotEmpty()) {
            api.newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            api.newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.showStatus = when (status?.lowercase()) {
                    "ongoing" -> com.lagradost.cloudstream3.ShowStatus.Ongoing
                    "completed" -> com.lagradost.cloudstream3.ShowStatus.Completed
                    else -> null
                }
            }
        }
    }

    // ---------- playback ----------

    private fun base64UrlDecode(s: String): ByteArray {
        val std = s.replace('-', '+').replace('_', '/')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    /** MegaPlay getSources "enc" -> master.m3u8 URL. Null on failure. */
    private fun decryptMegaEnc(enc: String): String? {
        return try {
            val raw = base64UrlDecode(enc)
            fun padKey(str: String, n: Int): ByteArray {
                val b = ByteArray(n)
                val src = str.toByteArray(Charsets.UTF_8)
                System.arraycopy(src, 0, b, 0, minOf(n, src.size))
                return b
            }
            val key = SecretKeySpec(padKey("i?LMTAx0Q6,:}50U", 32), "AES")
            val iv = IvParameterSpec(padKey("W0;27ToaUpl_P%'c", 16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val plain = cipher.doFinal(raw)
            JSONObject(String(plain, Charsets.UTF_8)).optString("file").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        api: MainAPI,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var hostDiag = "host:?"
        var bloggerDiag = "blogger:-"
        var megaDiag = "mega:-"
        var otherDiag = ""
        var engDiag = "eng:-"
        var araDiag = "ara:-"
        var storeDiag = "store:-"
        var trDiag = "tr:-"
        var engCuesDiag = "engcues:-"
        var epTitle = ""
        var epNum: Int? = null
        try {
            val epHtml = fetchHtml(data)
            epTitle = Regex("""<title>([^<]+) - Gogoanime</title>""").find(epHtml)?.groupValues?.get(1)
                ?.let { decodeEntities(it) } ?: ""
            epNum = epNumFromUrl(data)
            val seriesTitle = epTitle.substringBefore(" Episode ").ifEmpty { epTitle }
            val season = Regex("""Season (\d+)""").find(seriesTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val iframe = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(epHtml)?.groupValues?.get(1) ?: ""
            hostDiag = when {
                iframe.contains("blogger.com/video.g") -> "host:blogger"
                iframe.contains("megaplay.buzz") -> "host:megaplay"
                iframe.contains("megaup.cc") -> "host:megaup"
                iframe.contains("gogoanime.org.es") -> "host:orges"
                iframe.isEmpty() -> "host:none"
                else -> "host:other"
            }

            when {
                iframe.contains("blogger.com/video.g") -> {
                    // Blogger video page -> unicode-escaped JSON with
                    // "itag":22/18 "url" googlevideo links.
                    try {
                        val bHtml = app.get(
                            iframe,
                            headers = mapOf(
                                "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                                "Referer" to "https://www.blogger.com/"
                            )
                        ).text
                        val unesc = bHtml.replace("\\u003d", "=").replace("\\u0026", "&")
                            .replace("\\/", "/").replace("\\&quot;", "\"")
                        val vpRe = Regex(""""itag":(\d+),"url":"(https://[^"]*?videoplayback[^"]*?)"""")
                        val found = HashSet<String>()
                        for (m in vpRe.findAll(unesc)) {
                            val itag = m.groupValues[1]
                            val vUrl = m.groupValues[2]
                            if (!found.add(itag)) continue
                            val quality = when (itag) {
                                "22" -> Qualities.P720.value
                                "18" -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                            val label = when (itag) {
                                "22" -> "720p"
                                "18" -> "360p"
                                else -> "mp4"
                            }
                            callback(
                                newExtractorLink(
                                    "GogoAnime",
                                    "Blogger $label",
                                    vUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.blogger.com/"
                                    this.quality = quality
                                }
                            )
                        }
                        bloggerDiag = if (found.isEmpty()) "blogger:0" else "blogger:${found.sorted().joinToString(",")}"
                    } catch (e: Exception) {
                        bloggerDiag = "blogger:err"
                    }
                }
                iframe.contains("megaplay.buzz") -> {
                    try {
                        val mpHtml = try {
                            app.get(
                                iframe,
                                headers = headers + ("Referer" to "$mainUrl/")
                            ).text
                        } catch (e: Exception) {
                            app.get(iframe, headers = headers + ("Referer" to "$mainUrl/"), interceptor = cloudflareKiller).text
                        }
                        val dataId = Regex("""data-id="(\d+)"""").find(mpHtml)?.groupValues?.get(1)
                        if (dataId != null) {
                            val gsText = try {
                                app.get(
                                    "https://megaplay.buzz/stream/getSources?id=$dataId&s=bcdn",
                                    headers = mapOf(
                                        "User-Agent" to (headers["User-Agent"] ?: "Mozilla/5.0"),
                                        "Referer" to iframe,
                                        "X-Requested-With" to "XMLHttpRequest"
                                    )
                                ).text
                            } catch (e: Exception) {
                                ""
                            }
                            if (gsText.isNotEmpty()) {
                                val gs = JSONObject(gsText)
                                val tracks = gs.optJSONArray("tracks")
                                if (tracks != null) {
                                    for (i in 0 until tracks.length()) {
                                        val t = tracks.optJSONObject(i) ?: continue
                                        if (t.optString("kind") == "captions" && t.optString("label") == "English") {
                                            val vtt = t.optString("file")
                                            if (vtt.isNotEmpty()) {
                                                subtitleCallback(SubtitleFile("English", vtt))
                                                engDiag = "eng:site"
                                            }
                                        }
                                    }
                                }
                                val master = gs.optString("enc").ifEmpty { null }?.let { decryptMegaEnc(it) }
                                if (master != null) {
                                    callback(
                                        newExtractorLink(
                                            "GogoAnime",
                                            "MegaPlay 1080p",
                                            master,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://megaplay.buzz/"
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    megaDiag = "mega:ok"
                                } else megaDiag = "mega:dec-fail"
                            } else megaDiag = "mega:gs-empty"
                        } else megaDiag = "mega:no-id"
                    } catch (e: Exception) {
                        megaDiag = "mega:err"
                    }
                }
                iframe.contains("megaup.cc") || iframe.contains("gogoanime.org.es") -> {
                    // Unverifiable from the sandbox (522 / conn fail);
                    // best-effort: look for a direct stream in the page.
                    try {
                        val otherHtml = fetchHtml(iframe, referer = "$mainUrl/")
                        val m3u8 = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").find(otherHtml)?.value
                        val mp4 = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""").find(otherHtml)?.value
                        val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(otherHtml)?.groupValues?.get(1)
                        val direct = m3u8 ?: mp4 ?: file
                        if (!direct.isNullOrEmpty()) {
                            callback(
                                newExtractorLink(
                                    "GogoAnime",
                                    "GogoAnime",
                                    fix(direct),
                                    if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = iframe
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            otherDiag = "other:ok"
                        } else otherDiag = "other:unresolved"
                    } catch (e: Exception) {
                        otherDiag = "other:err"
                    }
                }
                iframe.isEmpty() -> {
                    otherDiag = "other:no-iframe"
                }
                else -> {
                    otherDiag = "other:unknown-host"
                }
            }

            // Subtitle layer (shared AnimeSubs): Arabic from
            // OpenSubtitles, merged AR+EN via the relay, phone-IP
            // translate fallback — same flow as Miruro v11/v12.
            try {
                val cleanTitle = seriesTitle
                val araUrl = if (cleanTitle.isNotEmpty() && epNum != null)
                    AnimeSubs.osSubFor(cleanTitle, season, epNum, "ara") else null
                araUrl?.let { subtitleCallback(SubtitleFile("العربية", it)) }
                val engOsUrl = if (cleanTitle.isNotEmpty() && epNum != null)
                    AnimeSubs.osSubFor(cleanTitle, season, epNum, "eng") else null
                val araUrls = if (cleanTitle.isNotEmpty() && epNum != null)
                    AnimeSubs.osSubsFor(cleanTitle, season, epNum, "ara") else emptyList()
                if (engDiag == "eng:-") engDiag = if (engOsUrl != null) "eng:os" else "eng:-"
                araDiag = "ara:${araUrls.size}"
                if (engOsUrl != null && araUrls.isNotEmpty()) {
                    val mergeUrl = StringBuilder("${AnimeSubs.relayBase}/relay/mergesubs?eng=")
                        .append(URLEncoder.encode(engOsUrl, "UTF-8"))
                    for (u in araUrls) mergeUrl.append("&ara=").append(URLEncoder.encode(u, "UTF-8"))
                    subtitleCallback(SubtitleFile("عربي + English", mergeUrl.toString()))
                    storeDiag = "merge:ok"
                } else if (engOsUrl != null) {
                    val engText = AnimeSubs.fetchSubText(engOsUrl, "", cloudflareKiller).first
                    val cues = if (engText.isNotEmpty()) AnimeSubs.parseSubCues(engText) else emptyList()
                    engCuesDiag = "engcues:${cues.size}"
                    if (cues.isNotEmpty()) {
                        // v21: Goojara v26 engine (gtx + MyMemory
                        // fallback, cached) — the old gtx-only call
                        // threw on the phone and produced nothing.
                        val res = AnimeTranslate.translateCues(cues, engOsUrl, engText)
                        if (res.arUrl != null) {
                            trDiag = if (AnimeTranslate.mmUsed) "tr:mm" else "tr:ok"
                            subtitleCallback(SubtitleFile("العربية (تلقائية)", res.arUrl))
                            res.dualUrl?.let { subtitleCallback(SubtitleFile("عربي + English", it)) }
                            storeDiag = if (res.dualUrl != null) "store:ok" else "store:ar-only"
                        } else {
                            trDiag = "tr:fail-${res.failStage ?: "gtx"}"
                            storeDiag = "store:tr-fail"
                        }
                    }
                }
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
        // DEBUG marker (standing rule): always emitted, wrapped so
        // nothing can kill it — if loadLinks runs at all, this
        // MUST appear in the source list.
        try {
            callback(
                newExtractorLink(
                    "GogoAnime",
                    "DEBUG v21 $hostDiag $bloggerDiag $megaDiag $otherDiag $engDiag $araDiag $storeDiag $trDiag $engCuesDiag",
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
