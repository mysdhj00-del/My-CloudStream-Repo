package com.movieboxin

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MovieBoxIN v2 — faithful clean-room reimplementation of the
 * CNC MovieBoxProviderIN flow (CNC Verse, verified live 2026-11-16:
 * still v56, 92,272 B, fileHash sha256-376043d8…8236ab — no newer
 * version; NO Kotlin source is published anywhere, everything here
 * was recovered from the dex + live traces, no code copied, no .cs3
 * redistributed). Study: ~/workspace/moviebox-study/NOTES.md,
 * trace: ~/workspace/movieboxin-addon/trace.py + evidence/.
 *
 * The chain, exactly as IN does it:
 * - HOME: dynamic server tabs via wefeed-mobile-bff/tab-operating
 *   (signed, region IN — the tabs on the user's screenshot:
 *   "Today's new updates", "✨Arabic Dubbed✨", "Boys' Love💙").
 *   Response: data.sections[] {title, type}; CUSTOM ->
 *   customData.subjects[].subject, BANNER -> banners[].subject,
 *   SUBJECTS_MOVIE -> subjects[]. The URL hardcodes page=1, so the
 *   home is one fetch, hasNext=false. Fallback when the mobile
 *   gateway 403s (region gate): the H5 twin on moviebox.sg
 *   (wefeed-h5api-bff/tab-operating -> data.operatingList[], same
 *   section model, subjects[] directly) — verified live 200 from
 *   the sandbox, 36 sections / 383 subjects, 2026-11-16.
 * - SEARCH: POST wefeed-mobile-bff/subject-api/search/v2 (signed,
 *   {"page":1,"perPage":20,"keyword":...}); fallback H5 POST
 *   wefeed-h5api-bff/subject/search (proven live: "Prison Break"
 *   -> Prison Break S1-S5 type 2 + films type 1).
 * - DETAIL: GET wefeed-h5api-bff/detail?detailPath= (movie-box.co
 *   twin in the dex; moviebox.sg used here, proven live) ->
 *   data.subject (+ data.resource.seasons[] {se, maxEp} for series;
 *   mobile season-info is the fallback when detail has none).
 *   Series episodes are generated 1..maxEp per season, all in ONE
 *   card, se/ep carried into playback.
 * - PLAYBACK: guest JWT (mobile gateway first, web host fallback)
 *   + X-Client-Token + x-tr-signature (HmacMD5 over the canonical
 *   string) -> subject/play on movie-box.co / moviebox.sg ->
 *   data.streams[]/dash[]/hls[] (signCookie per stream). Referer is
 *   the FULL player-page URL; detailPath goes RAW (no encoding);
 *   VIDEO links get Origin + full Referer + Pixel 9 Chrome 154 UA
 *   + the signed cookie under the stream's own signHeaderKey
 *   (default "X-MB-Token"); other types get Referer
 *   https://movie-box.co/ + desktop Chrome 154 UA. This exact
 *   shape is what made playback WORK on the user's phone in
 *   HorrorMovies v17 (2026-11-15 22:48) after v16 failed.
 * - SUBTITLES: MovieBox's own captions ONLY (his order 2026-11-16
 *   01:11/01:12 — they already exist ready-made, EN + AR; NO
 *   OpenSubtitles, NO translation pipeline, NO rebuild). H5
 *   caption endpoint first (moviebox.sg/wefeed-h5api-bff/subject/
 *   caption?format=&id=<real stream id> — the only caption route
 *   answering 200 from a US IP; id=subjectId alone returns an
 *   empty array, a missing id is a 400 "empty id"), mobile
 *   get-ext-captions / get-stream-captions as fallback. Caption
 *   object fields are lan / lanName ("Arabic"/"English", Latin) /
 *   url — there is NO "language" field. SubtitleFile is emitted
 *   as (lang, url) with NO headers.
 * - THE ONLY CHANGE vs the original IN (his order 2026-11-16):
 *   a DUAL option «الإنجليزية + العربية» merging the two
 *   existing MovieBox tracks (relay /relay/mergesubs first, local
 *   merge + /relay/store fallback). The singles stay exactly as
 *   they are. Options in his literal order: «الإنجليزية»,
 *   «العربية», «الإنجليزية + العربية» (dual). No trailer in v1
 *   AT ALL (his explicit order — he will ask for it himself if
 *   he wants it): no youtubeTrailerId, no trailerFor, no trailer
 *   code anywhere in this module.
 *
 * v2 (his three on-device v1 fixes, 2026-11-16):
 * - SEARCH is comprehensive now: every page of search/v2, then
 *   every page of H5 search (was: page 1 only, max 20). Proven
 *   live: "love" totalCount 452, pp.1-4 = 15/18/14/15 new cards.
 * - HOME paginates per row now: one mainPage entry per tab
 *   section; rank sections (genreTopId = the ranking-list id)
 *   paginate through H5 ranking-list/content (proven live:
 *   Horror Movies 17/15/18 new per page, zero overlap), with a
 *   per-section seen-set — a row stops only on a page with zero
 *   new cards. The "tabs" entry stays first (cache refresh +
 *   non-rank sections + session-1 expansion fallback).
 * - CAPTIONS are a UNION across every route now (v1 stopped at
 *   the first array holding Arabic, so English from another
 *   stream/route was never consulted — the Prison Break S1E1
 *   shape), and the mobile caption URLs match IN exactly
 *   (ext-captions: subjectId + resourceId + episode=0 hardcoded;
 *   stream-captions: subjectId + streamId only). The series
 *   detail lists English in its subtitles field, so the track
 *   is claimed to exist; whether S1E1's streams actually carry
 *   it can only be proven on his phone (play returns empty
 *   streams from a datacenter IP, so no real stream id exists
 *   in the sandbox). No OpenSubtitles, no rebuild — his order.
 *   Still NO trailer in v2.
 *
 * v3 (his on-device v2 verdict, 2026-11-16 02:59 — DEBUG:
 *   "MovieBoxIN v2 | srv=5 | ext=5 | t=3s | tok=mob | play=200 |
 *   se=1 | ep=1 | sub=none:h5=200-empty/x6/mob=400/mobstr=200"):
 * - CAPTIONS still missing on Prison Break S1E1. Proven live
 *   AFTER v2: the H5 caption route returns real ar+en tracks for
 *   EVERY stream id of S1E1 (all 4 MP4 ids -> 14 captions,
 *   DASH id + format=DASH -> 16; the .srt files fetch 200,
 *   36,910 B ar / 39,017 B en) — with the exact v2 URL shape
 *   and ANY header set. So the phone's 6x empty is an
 *   id-mismatch (its play response carries different stream
 *   ids), not a URL/header/format bug. Fix: try ALL stream ids
 *   (was: first 3) and LOG the tried ids in the DEBUG trail,
 *   so the next phone DEBUG shows the exact ids to test.
 * - Mobile ext-captions 400: v2 sent subjectId + episode=0
 *   (resourceId is null — the field exists in NO response:
 *   not in play data, not in streams, not in detail, not in
 *   search/tabs, and IN's dex never reads a "resourceId" JSON
 *   key). The gateway 400s on the missing param. Fix: try
 *   subjectId ONLY (mob1, the HorrorMovies v18 shape), then
 *   resourceId := first stream id + episode=0 (mob2); the
 *   IN-shape runs only if a real resourceId ever appears.
 *   Stream-captions (IN-exact, subjectId+streamId) now tries
 *   ALL stream ids, not just the first.
 * - "TOP 10" LIST INCOMPLETE: no section named "Top 10" exists
 *   in the live H5 tab-operating; the three 10-card sections
 *   (Hot Short TV / Trending Short Drama / Christmas Movies
 *   2026) all carry genreTopId and their ranking-lists paginate
 *   fully live (60/55/53 totals). The gap: rank pagination
 *   needs genreTopId, which is verified ONLY in the H5
 *   response — the phone's home loads from the MOBILE
 *   tab-operating, and IN's dex never reads "genreTopId" at
 *   all, so the mobile sections may not carry it (then
 *   hasNext=false for every row and no row ever expands).
 *   Fix: fetchTabSections merges genreTopId from the H5 twin
 *   into any mobile section missing it (match by opId, then
 *   title). Mobile-only sections with no H5 match stay
 *   first-page-only — the one case this cannot fix blind.
 *   Still NO trailer in v3.
 *
 * v4 — SUBTITLES (Prison Break S1E1, v3 DEBUG on his phone):
 * sub=none with ITS OWN ids logged (/ids=7599736117126382536,
 * ...), and that first phone id returns 200-empty from the
 * sandbox too, on both bases x all formats (T25) — while
 * WEB-play ids return 14 captions per MP4 id incl. ar+en,
 * 16 for DASH (T26b). So mobile-token play ids are a
 * different kind that carries NO captions at the H5 endpoint.
 * Fix: Phase B — when the play ids find nothing, mint a WEB
 * token, call play on the web base, and run the H5 route
 * with THOSE ids ("wh5=...", "/wids=..."); playback stays
 * on the mobile token. His hypothesis ("it fetches only the
 * Arabic source") is disproven by the DEBUG itself: v3 tried
 * every route (10 H5 + 7 mobile) with the phone's own ids
 * and found zero in BOTH languages — and no Arabic filter
 * exists anywhere in the home/ranking/caption code.
 * RECONCILED 2026-11-16 04:12 (his screenshot of the ORIGINAL
 * app's "اختر الترجمة" picker: Original Audio / Arabic sub /
 * French dub / esla dub / ptbr dub — NO "English sub"): the
 * picker IS the detail response's subject.dubs array (11
 * entries, verbatim lanNames, NO English-sub entry in ANY
 * title checked) — the app never offers an English sub.
 * "Arabic sub" is its own subject (8154005288807532328,
 * "Prison Break [ترجمة عربية] S1-S5", subtitles=''): a
 * separate encode with Arabic HARDCODED in the video — and
 * its play streams are EXACTLY the phone's ids
 * (7599736117126382536, 7270507275415437456, ... — T35), so
 * the phone plays the Arabic-sub encode, whose stream ids
 * carry NO sidecar captions anywhere (T36: 0 on both H5
 * bases x all 5 ids; v3 phone DEBUG: mobstr 200-empty).
 * The English .srt IS genuinely S1E1, though: the web-play
 * ids' ar+en files match the Pilot line-for-line ("That's
 * it." / "You're an artist, Sid." / "Keep your friends
 * close..." / "I've got 'em on me.", 2621 s = the play
 * duration) — so «الإنجليزية» + the dual are offered from
 * the caption store as he ordered, NOT from the app picker.
 * Caveat: if the phone keeps playing the hardcoded-Arabic
 * encode, the sidecar Arabic duplicates the burned-in one —
 * «الإنجليزية» alone is then the clean choice; the menu
 * still offers all three as ordered.
 * TOP 100: the grid is "Must-watch Top 100" (his screenshot)
 * — and it is the ORIGINAL APP's Categories ("تصنيف") screen,
 * not an H5 section: its 10 titles match NO H5 section and NO
 * H5 ranking-list (8505361996374835640 "Must-watch American
 * Black Drama" has exactly 38 unique titles, 18+19+1,
 * hasMore=false, zero overlap; "Popular Series" / Trending
 * Now has 125, missing 4 of the 10; Epic Fantasy 22, Teen
 * Fantasy 32). The 13 categoryType values in the v54 dex pair
 * to Trending / Trending in Cinema / Bollywood / South Indian /
 * Hollywood / Top Series This Week / Anime / Reality TV /
 * Indian Drama / Korean Drama / Chinese Drama / Western TV /
 * Turkish Drama — NO TOP100 string exists in either dex.
 * By elimination the Top 100 is the only "Top" ranking:
 * categoryType 4741626294545400336 ("Top Series This Week"),
 * series-only like the screenshot's 10 titles, rank = position
 * in the paged list. v4 wires it as its own section
 * ("Must-watch Top 100", mobile tab/ranking-list, full
 * pagination, dedup, stops only on a page with zero new
 * cards). NOT live-verified: the mobile gateway 403s from the
 * sandbox, so this identification rests on the dex + the
 * elimination above — his phone test decides. If the row shows
 * the wrong list, the true feed needs its exact categoryType
 * from the app itself. The genreTopId merge hardening stays
 * (section-url id + overlap matching, mirror-verified 23/23).
 * Still NO trailer in v4.
 *
 * HONEST LIMIT (proven live, see NOTES.md): the mobile gateway
 * (tab-operating, search/v2, season-info, captions) answers 403
 * "Service not available in current region" from a US datacenter
 * IP, and subject/play returns empty streams even with a valid
 * guest JWT — so playback + captions + the IN-region tabs can NOT
 * be proven from the sandbox. The H5 routes (home tabs, search,
 * detail, captions) DO answer from here and are the live-verified
 * fallbacks; the mobile routes are the ones that work on his
 * phone (the original v56 WORKS there, his word). Nothing here
 * is "working" until he confirms on his phone after a full close
 * and reopen. DEBUG ("MovieBoxIN v4 | ... | sub=...") is emitted
 * whenever links are zero OR the dual is missing, so a failure
 * is never silent.
 */
class MovieBoxINProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBoxIN"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /** Home sections, cached from the last tab-operating fetch.
     * One mainPage entry per section: rank sections (a
     * genreTopId — the ranking-list id, e.g. Horror Movies =
     * 8614980535946986176) paginate through the H5 ranking-list;
     * tab sections (no genreTopId) show their tab-operating
     * subjects only. The "tabs" entry is always first, so every
     * home load refreshes the cache and serves the non-rank
     * sections. The app freezes mainPage when the home is first
     * opened, so on the very first session only "tabs" exists
     * and row expansion falls back to getMainPage(page, "tabs")
     * (all sections' next page at once); from the second home
     * open the per-section entries exist and expansion is
     * precise per row. */
    private data class MbHomeSection(
        val title: String,
        val genreTopId: String,
        val opId: String,
        val subjects: List<SearchResponse>
    )

    @Volatile
    private var mbHomeSections: List<MbHomeSection> = emptyList()

    override val mainPage: List<MainPageData>
        get() {
            val entries = ArrayList<MainPageData>()
            entries += MainPageData("MovieBoxIN", "tabs", true)
            // TOP100 is a fixed entry (not a tab-operating
            // section): it exists from the first session, so the
            // app calls getMainPage(1, "cat:...") for it on home
            // load, and sideways scrolling calls page 2, 3, ...
            entries += MainPageData(
                mbTop100Title, "cat:$mbTop100CategoryType", true
            )
            for (s in mbHomeSections) {
                val data = if (s.genreTopId.isNotBlank()) "rank:${s.genreTopId}"
                else "tab:${s.opId}"
                entries += MainPageData(s.title, data, true)
            }
            return entries
        }

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val mbWebBase = "https://moviebox.sg"
    private val mbMobileBase = "https://apig.inmoviebox.com"
    private val mbApi3Base = "https://api3.aoneroom.com"
    private val mbSubsRelayBase =
        "https://anime-subs-relay.mysdhj00-del.deno.net"
    private val mbRankCategoryType = "4516404531735022304"
    /** TOP100 (v4): the only "Top" ranking in the v54 dex —
     * categoryType 4741626294545400336 ("Top Series This Week").
     * The 13 dex values pair to Trending / Trending in Cinema /
     * Bollywood / South Indian / Hollywood / Top Series This
     * Week / Anime / Reality TV / Indian Drama / Korean Drama /
     * Chinese Drama / Western TV / Turkish Drama — no TOP100
     * string exists in either dex, and the grid's 10 titles
     * match no H5 section or ranking-list (see NOTES.md v4).
     * Identification is by elimination, NOT live-verified
     * (mobile gateway 403s from the sandbox); the phone test
     * decides. Title is his grid title, verbatim. */
    private val mbTop100CategoryType = "4741626294545400336"
    private val mbTop100Title = "Must-watch Top 100"
    private val mbJsonHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "application/json, text/plain, */*"
    )
    private val mbUaToken =
        "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; " +
            "sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)"
    private val mbUaPlayChrome =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    /** Arabic-name normalizer (diacritics/tatweel stripped) — only
     * used for caption lanName matching (see mbIsArabicCaption). */
    private fun normalizeArabic(s: String): String {
        var t = s.replace(Regex("""[\u064B-\u0652\u0670\u0640]"""), "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
        return t
    }

    // ---------------------------------------------------------------
    // Home — dynamic tabs (IN: tab-operating; H5 twin as fallback)
    // ---------------------------------------------------------------
    /** One subject JSON object -> a card. subjectType 2 is a
     * series, everything else a movie (the dex mapping: 1 -> Movie,
     * 2 -> TvSeries, default -> Movie). */
    private fun parseSubject(o: JSONObject): SearchResponse? {
        val dp = o.optString("detailPath")
        if (dp.isBlank()) return null
        val title = o.optString("title")
        if (title.isBlank()) return null
        val poster = o.optJSONObject("cover")
            ?.optString("url")?.ifBlank { null }
        val url = "$mbWebBase/ar/moviedetail/$dp"
        return if (o.optInt("subjectType", 1) == 2) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    /** Subjects of one tab-operating section, tolerant of both
     * shapes: mobile (CUSTOM -> customData.subjects[].subject,
     * BANNER -> banners[].subject, SUBJECTS_MOVIE -> subjects[])
     * and H5 (operatingList items carry subjects[] directly and
     * banner.items[] with a nested "subject" or top-level fields).
     * Dedup inside the section by detailPath. */
    private fun sectionSubjects(section: JSONObject): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        fun addOne(o: JSONObject?) {
            if (o == null) return
            val subj = o.optJSONObject("subject") ?: o
            val dp = subj.optString("detailPath")
            if (dp.isBlank() || !seen.add(dp)) return
            parseSubject(subj)?.let { out += it }
        }
        fun addArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) addOne(arr.optJSONObject(i))
        }
        val type = section.optString("type")
        when (type) {
            "CUSTOM" -> {
                val cd = section.optJSONObject("customData")
                if (cd != null) {
                    addArray(cd.optJSONArray("subjects"))
                    if (cd.has("subject")) addOne(cd.optJSONObject("subject"))
                    if (out.isEmpty() && cd.optString("detailPath").isNotBlank()) {
                        addOne(cd)
                    }
                }
            }
            "BANNER" -> {
                val banner = section.optJSONObject("banner")
                addArray(banner?.optJSONArray("items"))
                addArray(section.optJSONArray("banners"))
            }
            else -> addArray(section.optJSONArray("subjects"))
        }
        if (out.isEmpty()) {
            // Generic fallback: whatever subject array the section
            // carries (covers APPOINTMENT_LIST and any new type).
            addArray(section.optJSONArray("subjects"))
            if (out.isEmpty()) {
                val cd = section.optJSONObject("customData")
                if (cd != null) addArray(cd.optJSONArray("subjects"))
            }
        }
        return out
    }

    /** Mobile tab-operating (IN's own route, signed, region IN).
     * Returns the sections array, or null on any failure. */
    private suspend fun fetchMobileTabs(): JSONArray? {
        return runCatching {
            // The dex URL hardcodes page=1&tabId=0 and appends a
            // version after "version="; the version code is used
            // (the version name is the other candidate if the
            // server ever rejects this).
            val url = "$mbApi3Base/wefeed-mobile-bff/tab-operating" +
                "?page=1&tabId=0&version=50020042"
            val headers = mbSignedHeaders(
                "GET", url, mbUaToken, mbClientInfoToken()
            ) + mapOf("x-play-mode" to "2")
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("sections")
        }.getOrNull()
    }

    /** H5 tab-operating twin (moviebox.sg, no signing, no token).
     * Verified live 200 from the sandbox, 2026-11-16: 36 sections,
     * 383 subjects. Returns the operatingList array, or null. */
    private suspend fun fetchH5Tabs(): JSONArray? {
        return runCatching {
            val url = "$mbWebBase/wefeed-h5api-bff/tab-operating" +
                "?page=1&tabId=0"
            val res = app.get(url, headers = mbJsonHeaders)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("operatingList")
        }.getOrNull()
    }

    /** Seen detailPaths per ranking-list section (keyed by
     * genreTopId) — the dedup that stops a section only when a
     * page brings zero NEW cards. Seeded with the tab-operating
     * subjects on the tabs fetch (so a session-1 page-2 call
     * never re-emits a card the row already shows), cleared on
     * page<=1 of the ranking-list path (fresh start). */
    private val mbRankSeen = HashMap<String, MutableSet<String>>()
    private val mbRankSeenLock = Any()

    /** Tab-operating sections as home records (mobile first, H5
     * twin fallback). Titles deduped (first non-empty wins —
     * the app keys rows by title); empty sections skipped.
     * Populates mbHomeSections (the mainPage entries for the
     * next home load) and seeds the rank seen-sets.
     * v3 — genreTopId merge: rank pagination needs genreTopId,
     * which is verified ONLY in the H5 response (IN's dex never
     * reads the field at all), while the phone's home loads from
     * the MOBILE tab-operating — if its sections don't carry it,
     * hasNext is false for every row and no row ever expands
     * (the "top 10 incomplete" shape). So any section missing
     * genreTopId takes it from the H5 twin (match by opId, then
     * title). Mobile-only sections with no H5 match keep a
     * blank id and stay first-page-only. Seeding runs AFTER the
     * merge so merged sections seed too.
     * v4 — two more merge routes: the ranking-list id is also
     * extracted from the section url (?id=<gid>), and an
     * overlap fallback matches by shared detailPaths when
     * opId AND title both fail (mirror-verified 23/23, 0 false
     * positives). The mobile "Must-watch Top 100" section
     * still has no H5 twin — its 10 titles match no H5
     * section — so it stays first-page-only until its
     * ranking-list id is reachable; see NOTES.md. */
    private suspend fun fetchTabSections(): List<MbHomeSection> {
        val sections = fetchMobileTabs() ?: fetchH5Tabs() ?: return emptyList()
        val out = ArrayList<MbHomeSection>()
        val seenTitles = HashSet<String>()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val cards = sectionSubjects(section)
            if (cards.isEmpty()) continue
            val title = section.optString("title").ifBlank { "✨Top Picks" }
            if (!seenTitles.add(title)) continue
            // v4: a mobile section may carry its ranking-list id
            // in its url (?id=<gid>, the website's ranking-list
            // link shape) even when it has no genreTopId field.
            var gid = section.optString("genreTopId")
            if (gid.isBlank()) {
                gid = Regex("[?&]id=(\\d+)").find(section.optString("url"))
                    ?.groupValues?.getOrNull(1).orEmpty()
            }
            out += MbHomeSection(
                title, gid,
                section.optString("opId"), cards
            )
        }
        if (out.any { it.genreTopId.isBlank() }) {
            val h5 = runCatching { fetchH5Tabs() }.getOrNull()
            if (h5 != null) {
                val byOp = HashMap<String, String>()
                val byTitle = HashMap<String, String>()
                for (i in 0 until h5.length()) {
                    val s = h5.optJSONObject(i) ?: continue
                    val gid = s.optString("genreTopId")
                    if (gid.isBlank()) continue
                    val op = s.optString("opId")
                    if (op.isNotBlank()) byOp[op] = gid
                    val t = s.optString("title")
                    if (t.isNotBlank()) byTitle[t] = gid
                }
                for (i in out.indices) {
                    val s = out[i]
                    if (s.genreTopId.isNotBlank()) continue
                    val gid = byOp[s.opId] ?: byTitle[s.title]
                    if (gid != null) out[i] = s.copy(genreTopId = gid)
                }
                // Overlap fallback (v4): a mobile section whose
                // H5 twin has a different title AND opId — e.g.
                // mobile "Must-watch Top 100" vs H5 "Must-watch
                // Black Shows" — match by shared detailPaths:
                // the H5 section sharing the most subjects
                // (at least 3, and at least half of the smaller
                // set) lends its genreTopId. Mirror-verified on
                // live T28 data: 23/23 recoveries with corrupted
                // title+opId, 0 false positives
                // (trace6_mirror.py). A section whose content
                // exists in NO H5 section stays unmatched —
                // honestly (the Top 100 case: its 10 titles
                // match no H5 section at all).
                if (out.any { it.genreTopId.isBlank() }) {
                    fun dpsOf(cards: List<SearchResponse>): Set<String> {
                        return cards.mapNotNullTo(HashSet()) { c ->
                            c.url.substringAfter("/moviedetail/")
                                .substringBefore("?").substringBefore("#")
                                .trim().ifBlank { null }
                        }
                    }
                    val h5Sets = ArrayList<Pair<String, Set<String>>>()
                    for (i in 0 until h5.length()) {
                        val s = h5.optJSONObject(i) ?: continue
                        val gid = s.optString("genreTopId")
                        if (gid.isBlank()) continue
                        val dps = dpsOf(sectionSubjects(s))
                        if (dps.isNotEmpty()) h5Sets += gid to dps
                    }
                    for (i in out.indices) {
                        val s = out[i]
                        if (s.genreTopId.isNotBlank()) continue
                        val mine = dpsOf(s.subjects)
                        if (mine.size < 3) continue
                        var bestGid: String? = null
                        var bestN = 0
                        for ((gid, dps) in h5Sets) {
                            val n = mine.intersect(dps).size
                            if (n > bestN) {
                                bestGid = gid
                                bestN = n
                            }
                        }
                        if (bestGid != null) {
                            val bestSet =
                                h5Sets.first { it.first == bestGid }.second
                            val need = maxOf(
                                3, (minOf(mine.size, bestSet.size) + 1) / 2
                            )
                            if (bestN >= need) {
                                out[i] = s.copy(genreTopId = bestGid)
                            }
                        }
                    }
                }
            }
        }
        for (s in out) {
            if (s.genreTopId.isBlank()) continue
            synchronized(mbRankSeenLock) {
                val seen = mbRankSeen.getOrPut(s.genreTopId) { HashSet() }
                // Seed with detailPaths (the same key
                // fetchRankPage dedups on) so a session-1
                // page-2 call never re-emits a card the row
                // already shows.
                for (c in s.subjects) {
                    val dp = c.url.substringAfter("/moviedetail/")
                        .substringBefore("?").substringBefore("#").trim()
                    if (dp.isNotBlank()) seen.add(dp)
                }
            }
        }
        mbHomeSections = out
        return out
    }

    /** One page of a ranking-list section (H5, no token, proven
     * live 2026-11-16: Horror Movies pp.1-3 = 17/15/18 new
     * cards, Popular Series 16/15/13, zero overlap, hasMore
     * true throughout). Returns (newCards, hasMore): hasMore
     * is false when the pager says so OR the page brought zero
     * new cards (a clamped past-the-end page). */
    private suspend fun fetchRankPage(
        genreTopId: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        val url = "$mbWebBase/wefeed-h5api-bff/ranking-list/content" +
            "?id=$genreTopId&page=$page&perPage=20"
        val res = runCatching { app.get(url, headers = mbJsonHeaders) }
            .getOrNull() ?: return emptyList<SearchResponse>() to false
        if (res.code !in 200..299) return emptyList<SearchResponse>() to false
        val json = runCatching { JSONObject(res.text) }.getOrNull()
            ?: return emptyList<SearchResponse>() to false
        val data = json.optJSONObject("data")
            ?: return emptyList<SearchResponse>() to false
        val arr = data.optJSONArray("subjectList")
            ?: return emptyList<SearchResponse>() to false
        val pager = data.optJSONObject("pager")
        val hasMore = when (val hm = pager?.opt("hasMore")) {
            is Boolean -> hm
            is String -> hm.equals("true", ignoreCase = true)
            else -> false
        }
        val seen = synchronized(mbRankSeenLock) {
            mbRankSeen.getOrPut(genreTopId) { HashSet() }.also {
                if (page <= 1) it.clear()
            }
        }
        val out = ArrayList<SearchResponse>()
        synchronized(mbRankSeenLock) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dp = o.optString("detailPath")
                if (dp.isBlank() || !seen.add(dp)) continue
                parseSubject(o)?.let { out += it }
            }
        }
        return out to (hasMore && out.isNotEmpty())
    }

    /** One page of the mobile tab/ranking-list feed (v4 TOP100).
     * The dex's only ranking call: GET {mobile base}/
     * wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=
     * <ct>&page=<page>&perPage=20, signed headers (IN's shape)
     * + the guest token. apig first, api3 fallback — both are
     * mobile hosts; the phone reaches them (tok=mob proves the
     * token mint works there), the sandbox gets 403 on both.
     * Response items: data.subjects[] (dex shape) with
     * data.subjectList[] / items[] / list[] as fallbacks; the
     * pager is read tolerantly like fetchRankPage. Dedup by
     * detailPath in a per-feed seen-set (cleared on page 1);
     * hasNext is false when the pager says so OR the page
     * brought zero new cards, and pages stop at 10 (~200
     * cards — far past the 100 the list promises). Fail-soft:
     * any non-200 / unparsable shape returns empty + false. */
    private suspend fun fetchCatPage(
        categoryType: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        if (categoryType.isBlank() || page < 1 || page > 10) {
            return emptyList<SearchResponse>() to false
        }
        val (token, _) = mbGuestToken()
        val bases = listOf(mbMobileBase, mbApi3Base)
        for (base in bases) {
            val url = "$base/wefeed-mobile-bff/tab/ranking-list" +
                "?tabId=0&categoryType=$categoryType" +
                "&page=$page&perPage=20"
            val res = runCatching {
                val headers = mbSignedHeaders(
                    "GET", url, mbUaToken, mbClientInfoToken()
                ).toMutableMap()
                if (!token.isNullOrBlank()) {
                    headers["Authorization"] = "Bearer $token"
                    headers["X-MB-Token"] = token
                }
                app.get(url, headers = headers)
            }.getOrNull() ?: continue
            if (res.code !in 200..299) continue
            val json = runCatching { JSONObject(res.text) }.getOrNull()
                ?: continue
            val data = json.optJSONObject("data") ?: continue
            val arr = data.optJSONArray("subjects")
                ?: data.optJSONArray("subjectList")
                ?: data.optJSONArray("items")
                ?: data.optJSONArray("list")
                ?: continue
            val pager = data.optJSONObject("pager")
            val pagerMore = when (val hm = pager?.opt("hasMore")) {
                is Boolean -> hm
                is String -> hm.equals("true", ignoreCase = true)
                else -> null
            }
            val key = "cat:$categoryType"
            val seen = synchronized(mbRankSeenLock) {
                mbRankSeen.getOrPut(key) { HashSet() }.also {
                    if (page <= 1) it.clear()
                }
            }
            val out = ArrayList<SearchResponse>()
            synchronized(mbRankSeenLock) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val subj = o.optJSONObject("subject") ?: o
                    val dp = subj.optString("detailPath")
                    if (dp.isBlank() || !seen.add(dp)) continue
                    parseSubject(subj)?.let { out += it }
                }
            }
            val more = pagerMore ?: (arr.length() >= 20)
            return out to (more && out.isNotEmpty())
        }
        return emptyList<SearchResponse>() to false
    }

    /** The "tabs" request. Page 1 refreshes the tab cache and
     * returns every section's first page (hasNext = any rank
     * section exists). Page>1 is the session-1 expansion
     * fallback — the frozen mainPage has no per-section entries
     * yet, so the app re-calls "tabs": return every rank
     * section's next ranking-list page at once. */
    private suspend fun getMainPageTabs(page: Int): HomePageResponse {
        if (page <= 1) {
            val sections = fetchTabSections()
            if (sections.isEmpty()) return newHomePageResponse(emptyList(), false)
            val lists = ArrayList<HomePageList>()
            // TOP100 first page (mobile feed; fail-soft — the
            // row is still served by its own "cat:" entry).
            val (topCards, topMore) =
                fetchCatPage(mbTop100CategoryType, 1)
            if (topCards.isNotEmpty()) {
                lists += HomePageList(
                    mbTop100Title, topCards, isHorizontalImages = true
                )
            }
            lists += sections.map { s ->
                HomePageList(s.title, s.subjects, isHorizontalImages = true)
            }
            val hasNext = topMore ||
                sections.any { it.genreTopId.isNotBlank() }
            return newHomePageResponse(lists, hasNext)
        }
        var anyMore = false
        val lists = ArrayList<HomePageList>()
        val (topCards, topMore) = fetchCatPage(mbTop100CategoryType, page)
        if (topMore) anyMore = true
        if (topCards.isNotEmpty()) {
            lists += HomePageList(
                mbTop100Title, topCards, isHorizontalImages = true
            )
        }
        for (s in mbHomeSections) {
            if (s.genreTopId.isBlank()) continue
            val (cards, hasMore) = fetchRankPage(s.genreTopId, page)
            if (hasMore) anyMore = true
            lists += HomePageList(s.title, cards, isHorizontalImages = true)
        }
        return newHomePageResponse(lists, anyMore)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when {
            request.data == "tabs" -> getMainPageTabs(page)
            request.data.startsWith("rank:") -> {
                val gid = request.data.removePrefix("rank:")
                if (gid.isBlank()) return newHomePageResponse(emptyList(), false)
                val (cards, hasMore) = fetchRankPage(gid, page)
                newHomePageResponse(
                    listOf(
                        HomePageList(request.name, cards, isHorizontalImages = true)
                    ),
                    hasMore
                )
            }
            request.data.startsWith("cat:") -> {
                // TOP100 (v4): the mobile tab/ranking-list feed.
                // Page 1 serves the row on home load; pages 2+
                // are the sideways-scroll expansion (100 titles,
                // stops only on a page with zero new cards).
                val ct = request.data.removePrefix("cat:")
                if (ct.isBlank()) return newHomePageResponse(emptyList(), false)
                val (cards, hasMore) = fetchCatPage(ct, page)
                newHomePageResponse(
                    listOf(
                        HomePageList(request.name, cards, isHorizontalImages = true)
                    ),
                    hasMore
                )
            }
            request.data.startsWith("tab:") -> {
                // Non-rank sections (e.g. Coming Soon): their
                // tab-operating subjects only, no further pages.
                if (page > 1) return newHomePageResponse(emptyList(), false)
                val opId = request.data.removePrefix("tab:")
                var cards = mbHomeSections
                    .firstOrNull { it.opId == opId }?.subjects
                if (cards == null) {
                    fetchTabSections()
                    cards = mbHomeSections
                        .firstOrNull { it.opId == opId }?.subjects
                }
                newHomePageResponse(
                    listOf(
                        HomePageList(
                            request.name, cards ?: emptyList(),
                            isHorizontalImages = true
                        )
                    ),
                    false
                )
            }
            else -> newHomePageResponse(emptyList(), false)
        }
    }

    // ---------------------------------------------------------------
    // Search — mobile search/v2 first (IN), H5 search fallback
    // ---------------------------------------------------------------
    /** Parse one search page: (newItems, hasMore). Dedup by
     * detailPath against the caller's seen-set (the site itself
     * repeats titles across pages — different detailPaths are
     * different records). */
    private fun searchPageItems(
        root: JSONObject?,
        seen: MutableSet<String>
    ): Pair<List<SearchResponse>, Boolean> {
        if (root == null) return emptyList<SearchResponse>() to false
        val data = root.optJSONObject("data") ?: root
        val arr = data.optJSONArray("items")
            ?: data.optJSONArray("subjectList")
            ?: data.optJSONArray("list")
            ?: return emptyList<SearchResponse>() to false
        val pager = data.optJSONObject("pager")
        val hasMore = when (val hm = pager?.opt("hasMore")) {
            is Boolean -> hm
            is String -> hm.equals("true", ignoreCase = true)
            else -> false
        }
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dp = o.optString("detailPath")
            if (dp.isBlank() || !seen.add(dp)) continue
            parseSubject(o)?.let { out += it }
        }
        return out to hasMore
    }

    /** One page of one search route (mobile search/v2 when
     * mobile=true, H5 subject/search otherwise). Both routes
     * need the guest token (H5 without one is a 400 "invalid
     * token", proven live). */
    private suspend fun searchRoutePage(
        query: String,
        page: Int,
        mobile: Boolean,
        seen: MutableSet<String>
    ): Pair<List<SearchResponse>, Boolean> {
        return runCatching {
            val (token, _) = mbGuestToken()
            val body = JSONObject().apply {
                put("page", page)
                put("perPage", 20)
                put("keyword", query)
            }.toString()
            val res = if (mobile) {
                val url = "$mbApi3Base/wefeed-mobile-bff/subject-api/search/v2"
                val headers = mbSignedHeaders(
                    "POST", url, mbUaToken, mbClientInfoToken(),
                    body = body, contentType = "application/json; charset=utf-8"
                ).toMutableMap()
                if (!token.isNullOrBlank()) {
                    headers["Authorization"] = "Bearer $token"
                    headers["X-MB-Token"] = token
                }
                app.post(
                    url, headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
            } else {
                val headers = mutableMapOf(
                    "User-Agent" to browserUA,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                )
                if (!token.isNullOrBlank()) {
                    headers["Authorization"] = "Bearer $token"
                    headers["X-MB-Token"] = token
                }
                app.post(
                    "$mbWebBase/wefeed-h5api-bff/subject/search",
                    headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json".toMediaType()
                    )
                )
            }
            if (res.code !in 200..299) return emptyList<SearchResponse>() to false
            searchPageItems(
                runCatching { JSONObject(res.text) }.getOrNull(), seen
            )
        }.getOrDefault(emptyList<SearchResponse>() to false)
    }

    /** ALL pages of one search route. Stops on hasMore=false, a
     * page with zero NEW items, or 8 pages (~160 results — the
     * bound that keeps search fast). Proven live on H5:
     * "love" totalCount 452, pp.1-4 = 15/18/14/15 new cards;
     * "prison break" p1 11 + p2 7 new. v1 fetched page 1 only
     * (max 20, capped at 30 in the parser) — that was the
     * "limited results" complaint. */
    private suspend fun searchRoute(
        query: String,
        mobile: Boolean
    ): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (page in 1..8) {
            val (items, hasMore) = searchRoutePage(query, page, mobile, seen)
            out += items
            if (!hasMore || items.isEmpty()) break
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // Route 1 — IN's own search/v2 (signed POST on api3),
        // ALL pages now, not just page 1.
        val mob = searchRoute(query, mobile = true)
        if (mob.isNotEmpty()) return mob
        // Route 2 — H5 search, ALL pages.
        return searchRoute(query, mobile = false)
    }

    // ---------------------------------------------------------------
    // Detail — movies + series (one card, all episodes)
    // ---------------------------------------------------------------
    private fun mbData(
        url: String,
        title: String,
        subjectId: String,
        se: Int,
        ep: Int
    ): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return url + "|||" + enc + "|||" + subjectId +
            "|||" + se + "|||" + ep
    }

    private suspend fun movieFallback(url: String): LoadResponse {
        val name = url.trimEnd('/').substringAfterLast('/')
            .replace('-', ' ').trim().ifBlank { "MovieBox" }
        return newMovieLoadResponse(name, url, TvType.Movie, mbData(url, name, "", 0, 0))
    }

    /** Seasons from the mobile season-info endpoint (IN's route):
     * data.seasons[] {se, maxEp}. Null when the gateway 403s. */
    private suspend fun fetchMobileSeasons(subjectId: String): JSONArray? {
        return runCatching {
            val url = "$mbApi3Base/wefeed-mobile-bff/subject-api/season-info" +
                "?subjectId=$subjectId"
            val headers = mbSignedHeaders(
                "GET", url, mbUaToken, mbClientInfoToken()
            ).toMutableMap()
            val (token, _) = mbGuestToken()
            if (!token.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $token"
                headers["X-MB-Token"] = token
            }
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("seasons")
        }.getOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val dp = url.substringAfter("/moviedetail/")
            .substringBefore("?").substringBefore("#").trim()
        if (dp.isBlank()) return movieFallback(url)
        val json = runCatching {
            app.get(
                "$mbWebBase/wefeed-h5api-bff/detail?detailPath=" +
                    java.net.URLEncoder.encode(dp, "UTF-8"),
                headers = mbJsonHeaders
            ).text
        }.getOrNull()
        val data = runCatching {
            JSONObject(json ?: "").optJSONObject("data")
        }.getOrNull()
        val subj = data?.optJSONObject("subject")
            ?: return movieFallback(url)
        val title = subj.optString("title")
        val poster = subj.optJSONObject("cover")
            ?.optString("url")?.ifBlank { null }
        val desc = subj.optString("description")
        val genre = subj.optString("genre")
        val rating = subj.optDouble("imdbRatingValue", -1.0)
            .let { if (it >= 0) it.toString() else null }
        val plot = buildString {
            if (rating != null) append("★ التقييم: $rating/10\n")
            if (genre.isNotBlank()) append("النوع: $genre\n")
            if (desc.isNotBlank()) append(desc)
        }.ifBlank { null }
        val subjectId = subj.optString("subjectId")
        val isSeries = subj.optInt("subjectType", 1) == 2
        if (!isSeries) {
            return newMovieLoadResponse(
                title.ifBlank { "MovieBox" }, url, TvType.Movie,
                mbData(url, title, subjectId, 0, 0)
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
            }
        }
        // Series: seasons live under data.resource.seasons
        // (H5 detail, proven live: Prison Break S1-S5 -> S1 22,
        // S2 22, S3 13, S4 ...); mobile season-info is the
        // fallback when detail carries none.
        var seasons = data.optJSONObject("resource")
            ?.optJSONArray("seasons")
        if (seasons == null || seasons.length() == 0) {
            if (subjectId.isNotBlank()) {
                seasons = fetchMobileSeasons(subjectId)
            }
        }
        val episodes = ArrayList<Episode>()
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(s) ?: continue
                val se = seasonObj.optInt("se", 0)
                if (se <= 0) continue
                val maxEp = seasonObj.optInt("maxEp", 0)
                if (maxEp <= 0) continue
                for (ep in 1..maxEp) {
                    if (episodes.size >= 1000) break
                    episodes += newEpisode(
                        mbData(url, title, subjectId, se, ep)
                    ) {
                        this.name = "S${se}E$ep"
                        this.season = se
                        this.episode = ep
                    }
                }
            }
        }
        return newTvSeriesLoadResponse(
            title.ifBlank { "MovieBox" }, url, TvType.TvSeries, episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------
    // DEBUG + playback — shared machinery
    // ---------------------------------------------------------------
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

    // --- Auth (protocol constants recovered from the IN dex) ---
    private val mbDeviceId: String by lazy {
        val rnd = java.security.SecureRandom()
        val bytes = ByteArray(16)
        rnd.nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** The two HMAC keys, double-base64 as stored in the dex. */
    private val mbKeyDefaultB64 =
        "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private val mbKeyAltB64 =
        "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

    private fun mbKey(doubleB64: String): ByteArray {
        val once = Base64.decode(doubleB64, Base64.DEFAULT)
        val inner = String(once, Charsets.UTF_8).trim()
        return Base64.decode(inner, Base64.DEFAULT)
    }

    private fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(s)

    /** Canonical string: METHOD\nACCEPT\nCONTENT_TYPE\nBODY_LEN\n
     * TS\nBODY_HASH\nPATH[?sorted-query] — query keys sorted
     * alphabetically, each rendered name=value (decoded) joined
     * with '&'. GET with no body => BODY_LEN and BODY_HASH are
     * empty strings; POST => length + md5 of the first 102400
     * body bytes. */
    private fun mbCanonical(
        method: String,
        url: String,
        ts: Long,
        body: String?,
        accept: String,
        contentType: String
    ): String {
        val qStart = url.indexOf('?')
        val rawPath = if (qStart >= 0) url.substring(0, qStart) else url
        val path = Regex("""^[a-z]+://[^/]+""").replace(rawPath, "")
        val pairs = ArrayList<Pair<String, String>>()
        if (qStart >= 0) {
            val query = url.substring(qStart + 1)
            if (query.isNotBlank()) {
                for (part in query.split("&")) {
                    if (part.isEmpty()) continue
                    val eq = part.indexOf('=')
                    val k = if (eq >= 0) urlDecode(part.substring(0, eq))
                    else urlDecode(part)
                    val v = if (eq >= 0) urlDecode(part.substring(eq + 1))
                    else ""
                    pairs += k to v
                }
            }
        }
        val rendered = StringBuilder()
        for (k in pairs.map { it.first }.distinct().sorted()) {
            for ((kk, vv) in pairs) {
                if (kk == k) {
                    if (rendered.isNotEmpty()) rendered.append("&")
                    rendered.append(k).append("=").append(vv)
                }
            }
        }
        val curl = if (rendered.isEmpty()) path else "$path?$rendered"
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyLen = if (bodyBytes == null) "" else bodyBytes.size.toString()
        val bodyHash = if (bodyBytes == null) "" else
            md5Hex(bodyBytes.copyOf(minOf(bodyBytes.size, 102400)))
        return listOf(
            method.uppercase(), accept, contentType,
            bodyLen, ts.toString(), bodyHash, curl
        ).joinToString("\n")
    }

    /** X-Client-Token = "<ts>,<md5(reverse(ts))>"; x-tr-signature
     * = "<ts>|2|<base64(HmacMD5(key, canonical))>". */
    private fun mbSignedHeaders(
        method: String,
        url: String,
        ua: String,
        clientInfo: String,
        body: String? = null,
        accept: String = "application/json",
        contentType: String = "application/json"
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val xct = "$ts,${md5Hex(ts.toString().reversed().toByteArray(Charsets.UTF_8))}"
        val canonical = mbCanonical(method, url, ts, body, accept, contentType)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(mbKey(mbKeyDefaultB64), "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sig = "$ts|2|" + Base64.encodeToString(sigBytes, Base64.NO_WRAP)
        return mapOf(
            "User-Agent" to ua,
            "Accept" to accept,
            "Content-Type" to contentType,
            "X-Client-Token" to xct,
            "x-tr-signature" to sig,
            "X-Client-Info" to clientInfo,
            "X-Client-Status" to "0"
        )
    }

    private fun mbClientInfoToken(): String =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"3.0.03.0529.03\"," +
            "\"version_code\":50020042,\"os\":\"android\",\"os_version\":\"16\"," +
            "\"device_id\":\"" + mbDeviceId + "\",\"install_store\":\"ps\"," +
            "\"gaid\":\"d7578036d13336cc\",\"brand\":\"google\",\"model\":\"SM-S918B\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"IN\"," +
            "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

    private fun mbClientInfoPlay(): String =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"4.0.02.0831.03\"," +
            "\"version_code\":50020126,\"os\":\"android\",\"os_version\":\"14\"," +
            "\"install_ch\":\"official\",\"device_id\":\"" + mbDeviceId + "\"," +
            "\"install_store\":\"official\",\"gaid\":\"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d\"," +
            "\"brand\":\"Google\",\"model\":\"Pixel 8\",\"system_language\":\"en\"," +
            "\"net\":\"NETWORK_WIFI\",\"region\":\"IN\",\"timezone\":\"Asia/Calcutta\"," +
            "\"sp_code\":\"\",\"X-Play-Mode\":\"1\",\"X-Idle-Data\":\"1\"," +
            "\"X-Family-Mode\":\"0\",\"X-Content-Mode\":\"0\"}"

    /** Read the guest JWT from one call: the token arrives in the
     * "x-user" response header (a JSON string with a "token"
     * field) or, failing that, in the JSON body. */
    private suspend fun mbTokenFrom(
        url: String,
        signed: Boolean,
        clientInfo: String?,
        ua: String
    ): String? {
        return try {
            val headers = if (signed && clientInfo != null) {
                mbSignedHeaders("GET", url, ua, clientInfo)
            } else {
                mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json, text/plain, */*"
                )
            }
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return null
            val xu = res.headers["x-user"] ?: res.headers["X-User"]
            val fromHeader = xu?.let {
                runCatching { JSONObject(it).optString("token") }.getOrNull()
            }?.ifBlank { null }
            if (!fromHeader.isNullOrBlank()) return fromHeader
            val body = res.text
            if (body.isBlank()) return null
            val j = runCatching { JSONObject(body) }.getOrNull()
                ?: return null
            j.optString("token").ifBlank { null }
                ?: j.optJSONObject("data")?.optString("token")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var mbCachedToken: String? = null
    @Volatile
    private var mbCachedAt: Long = 0

    /** Guest JWT (valid ~90 days; cached 1 h per session). Mobile
     * gateway first (IN order), web host fallback — the web host
     * grants the same JWT in its x-user header (verified live). */
    private suspend fun mbGuestToken(): Pair<String?, String> {
        val now = System.currentTimeMillis()
        val cached = mbCachedToken
        if (cached != null && now - mbCachedAt < 3600_000) {
            return cached to "cached"
        }
        val mobUrl = "$mbMobileBase/wefeed-mobile-bff/tab/ranking-list" +
            "?tabId=0&categoryType=$mbRankCategoryType&page=1&perPage=1"
        var tok = mbTokenFrom(
            mobUrl, signed = true,
            clientInfo = mbClientInfoToken(), ua = mbUaToken
        )
        var via = "mob"
        if (tok == null) {
            val webUrl = "$mbWebBase/wefeed-h5api-bff/ranking-list/content" +
                "?id=8614980535946986176&page=1&perPage=1"
            tok = mbTokenFrom(
                webUrl, signed = false, clientInfo = null, ua = browserUA
            )
            via = "web"
        }
        if (tok != null) {
            mbCachedToken = tok
            mbCachedAt = now
        }
        return tok to via
    }

    private fun mbStreamUrl(s: JSONObject): String? {
        val direct = s.optString("url").trim()
        if (direct.isNotBlank()) return direct
        val arr = s.optJSONArray("resolutions") ?: return null
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("url").orEmpty().trim()
            if (u.isNotBlank()) return u
        }
        return null
    }

    /** CNC-faithful emit (MovieBoxProviderIN loadLinks$emitStream,
     * byte-identical logic in v54's emitNetStream). VIDEO links get
     * Origin + the full player-page Referer + the mobile Chrome UA
     * + the signed cookie under the stream's own signHeaderKey
     * (default "X-MB-Token"); every other type gets {Referer:
     * "https://movie-box.co/", desktop Chrome 154 UA}.
     * subDomain = the play base that returned the streams,
     * subPath = detailPath, so the emitted Referer is exactly the
     * player-page URL the backend validated for the play call. */
    private suspend fun emitMovieBoxLink(
        url: String,
        label: String,
        signCookie: String?,
        signHeaderKey: String?,
        playBase: String,
        detailPath: String,
        subjectId: String,
        se: Int,
        ep: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        // Movies play with se=0&ep=0 and the dex Referer carries
        // empty detailSe/detailEp (the v17 shape proven on-device);
        // episodes carry their real se/ep in both places.
        val seEp = if (se == 0 && ep == 0) {
            "detailSe=&detailEp="
        } else {
            "detailSe=$se&detailEp=$ep"
        }
        val playerReferer = "$playBase/movies/$detailPath?id=$subjectId" +
            "&type=/movie/detail&$seEp&lang=en"
        val headers: Map<String, String> =
            if (type != ExtractorLinkType.VIDEO) {
                mapOf(
                    "Referer" to "https://movie-box.co/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
                )
            } else {
                val m = LinkedHashMap<String, String>()
                m["Origin"] = playBase
                m["Referer"] = playerReferer
                m["User-Agent"] = mbUaPlayChrome
                if (!signCookie.isNullOrBlank()) {
                    val key = signHeaderKey?.ifBlank { null } ?: "X-MB-Token"
                    m[key] = signCookie
                }
                m
            }
        val quality = when {
            label.contains("2160") -> Qualities.P2160.value
            label.contains("1440") -> Qualities.P1440.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        runCatching {
            callback(
                newExtractorLink(
                    name, label, url, type
                ) {
                    this.referer = if (type != ExtractorLinkType.VIDEO) {
                        "https://movie-box.co/"
                    } else {
                        playerReferer
                    }
                    this.quality = quality
                    this.headers = headers
                }
            )
        }
    }

    // ---------------------------------------------------------------
    // Subtitles — MovieBox's own captions ONLY (+ the dual merge).
    // No OpenSubtitles, no translation pipeline, no rebuild: the
    // singles come from MovieBox exactly as they are; the ONLY
    // addition vs the original IN is the dual option merging the
    // two existing tracks (his order 2026-11-16).
    // ---------------------------------------------------------------
    /** Is this caption object an Arabic track? Fields (proven from
     * the CNC dex + the CloudX model) are lan (code, e.g. "ar"),
     * lanName (display name, e.g. "Arabic"), url — there is NO
     * "language" field, and lanName is Latin ("Arabic"). */
    private fun mbIsArabicCaption(o: JSONObject): Boolean {
        val lan = o.optString("lan").lowercase()
        val lang = o.optString("language").lowercase()
        if (lan == "ar" || lan == "ara" || lang == "ar" || lang == "ara") return true
        val lanName = o.optString("lanName")
        if (normalizeArabic(lanName).contains("عرب")) return true
        if (lanName.lowercase().contains("arab")) return true
        return false
    }

    private fun mbIsEnglishCaption(o: JSONObject): Boolean {
        val lan = o.optString("lan").lowercase()
        val lang = o.optString("language").lowercase()
        if (lan == "en" || lan == "eng" || lang == "en" || lang == "eng") return true
        val lanName = o.optString("lanName")
        if (lanName.lowercase().contains("english")) return true
        if (normalizeArabic(lanName).contains("انجليز")) return true
        return false
    }

    /** Scan one captions array for the FIRST Arabic and the FIRST
     * English track. Returns (araUrl, engUrl) — either may be
     * null. Both come from the SAME array (the same caption call
     * that returns Arabic returns English too). */
    private fun mbPickCaptions(
        arr: JSONArray?
    ): Pair<String?, String?> {
        var ara: String? = null
        var eng: String? = null
        if (arr == null) return null to null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val capUrl = o.optString("url")
            if (capUrl.isBlank()) continue
            if (ara == null && mbIsArabicCaption(o)) ara = capUrl
            if (eng == null && mbIsEnglishCaption(o)) eng = capUrl
            if (ara != null && eng != null) break
        }
        return ara to eng
    }

    // ---- Local subtitle merge (fallback when the relay mergesubs
    // URL does not serve) ----
    private data class MbSubCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private fun mbSubTsToMs(t: String): Long {
        val clean = t.trim().replace(',', '.')
        val parts = clean.split(":")
        val secParts = parts.last().split(".")
        val secs = secParts[0].toLongOrNull() ?: 0L
        val ms = (secParts.getOrNull(1) ?: "0").padEnd(3, '0')
            .take(3).toLongOrNull() ?: 0L
        var total = secs * 1000 + ms
        if (parts.size == 3) total += (parts[0].toLongOrNull() ?: 0L) * 3600000 +
            (parts[1].toLongOrNull() ?: 0L) * 60000
        else if (parts.size == 2) total += (parts[0].toLongOrNull() ?: 0L) * 60000
        return total
    }

    private fun mbFmtMs(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return "%02d:%02d:%02d.%03d".format(h, m, s, r)
    }

    /** Tolerant VTT/SRT cue parser (SRT index lines and comma
     * timestamps are skipped/accepted; HTML tags stripped). */
    private fun mbParseSubCues(text: String): List<MbSubCue> {
        val cues = ArrayList<MbSubCue>()
        val blocks = text.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n\n")
        val tsRe = Regex(
            """(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}|\d{1,2}:\d{2}[.,]\d{1,3})"""
        )
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var tsIdx = -1
            var m: MatchResult? = null
            for (i in lines.indices) {
                m = tsRe.find(lines[i])
                if (m != null) {
                    tsIdx = i
                    break
                }
            }
            if (tsIdx < 0 || m == null) continue
            val startMs = mbSubTsToMs(m.groupValues[1])
            val endMs = mbSubTsToMs(m.groupValues[2])
            val cueText = lines.subList(tsIdx + 1, lines.size)
                .joinToString("\n") { it.replace(Regex("<[^>]*>"), "") }
                .trim()
            if (cueText.isEmpty()) continue
            cues += MbSubCue(startMs, endMs, cueText)
        }
        return cues
    }

    /** Merge Arabic + English cue lists into one dual VTT:
     * every Arabic cue gains the overlapping English line(s)
     * underneath it; English cues with no Arabic overlap stay
     * on their own. Same shape the relay's mergesubs returns
     * (Arabic line first, English second). */
    private fun mbBuildDualVTT(
        araCues: List<MbSubCue>,
        engCues: List<MbSubCue>
    ): String {
        val usedEng = HashSet<Int>()
        val merged = ArrayList<MbSubCue>()
        for (a in araCues) {
            val overlapping = engCues.withIndex()
                .filter { it.value.startMs < a.endMs && it.value.endMs > a.startMs }
            val enText = overlapping.joinToString("\n") { it.value.text }
            overlapping.forEach { usedEng += it.index }
            val text = if (enText.isEmpty()) a.text else a.text + "\n" + enText
            merged += MbSubCue(a.startMs, a.endMs, text)
        }
        for ((idx, e) in engCues.withIndex()) {
            if (idx !in usedEng) merged += MbSubCue(e.startMs, e.endMs, e.text)
        }
        merged.sortBy { it.startMs }
        val out = StringBuilder("WEBVTT\n\n")
        for (c in merged) {
            out.append(mbFmtMs(c.startMs)).append(" --> ")
                .append(mbFmtMs(c.endMs)).append("\n")
                .append(c.text).append("\n\n")
        }
        return out.toString()
    }

    /** Fetch both caption files on the phone's network (which
     * reaches MovieBox), merge locally and host the result on
     * the subtitle relay. Returns the stored dual URL, or null
     * when any step fails. */
    private suspend fun mbMergeCaptionsLocal(
        araUrl: String,
        engUrl: String
    ): String? {
        return runCatching {
            val araText = app.get(araUrl).text
            val engText = app.get(engUrl).text
            if (araText.isBlank() || engText.isBlank()) return@runCatching null
            val araCues = mbParseSubCues(araText)
            val engCues = mbParseSubCues(engText)
            if (araCues.isEmpty() || engCues.isEmpty()) return@runCatching null
            val vtt = mbBuildDualVTT(araCues, engCues)
            val res = app.post(
                "$mbSubsRelayBase/relay/store",
                data = mapOf("vtt" to vtt)
            )
            JSONObject(res.text).optString("url").ifEmpty { null }
        }.getOrNull()
    }

    /** Emit English, Arabic and (when both exist) the dual from
     * already-found caption URLs, in his literal order:
     * «الإنجليزية», «العربية», «الإنجليزية + العربية» (dual).
     * Returns the sub diagnostic: "ar+en" when the dual option
     * was emitted, "ar" / "en" for a single side, "ar+mf" when
     * both sides exist but no dual file could be built (merge +
     * local fallback both failed — the singles are still
     * emitted). */
    private suspend fun mbEmitUnion(
        ara: String?,
        eng: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        if (eng != null) subtitleCallback(SubtitleFile("الإنجليزية", eng))
        if (ara != null) subtitleCallback(SubtitleFile("العربية", ara))
        if (ara != null && eng != null) {
            val mergeUrl = "$mbSubsRelayBase/relay/mergesubs?eng=" +
                java.net.URLEncoder.encode(eng, "UTF-8") +
                "&ara=" + java.net.URLEncoder.encode(ara, "UTF-8")
            val mergeOk = runCatching {
                val r = app.get(mergeUrl)
                r.code in 200..299 && r.text.contains("WEBVTT")
            }.getOrDefault(false)
            if (mergeOk) {
                subtitleCallback(SubtitleFile("الإنجليزية + العربية", mergeUrl))
                return "ar+en"
            }
            val stored = mbMergeCaptionsLocal(ara, eng)
            if (stored != null) {
                subtitleCallback(SubtitleFile("الإنجليزية + العربية", stored))
                return "ar+en"
            }
            return "ar+mf"
        }
        if (ara != null) return "ar"
        return "en"
    }

    /** Captions array from a caption-endpoint body: the mobile
     * shape (data.extCaptions) or the H5 shape (data.captions),
     * plus top-level fallbacks. */
    private fun mbCaptionsArray(
        json: JSONObject?,
        vararg keys: String
    ): JSONArray? {
        if (json == null) return null
        val data = json.optJSONObject("data")
        for (k in keys) {
            data?.optJSONArray(k)?.let { return it }
            json.optJSONArray(k)?.let { return it }
        }
        return null
    }

    @Volatile
    private var mbCachedWebToken: String? = null
    @Volatile
    private var mbCachedWebAt: Long = 0

    /** Guest JWT minted from the WEB host only (moviebox.sg
     * ranking-list/content, unsigned, browser UA — proven live
     * from the sandbox in v1 and re-verified in v4: the web
     * host grants the same guest JWT in its x-user header).
     * The phone's normal token is the mobile one (tok=mob);
     * only the web token yields caption-carrying stream ids
     * (v4 T25/T26b). Cached 1 h per session, separately from
     * the mobile token. */
    private suspend fun mbWebToken(): String? {
        val now = System.currentTimeMillis()
        val cached = mbCachedWebToken
        if (cached != null && now - mbCachedWebAt < 3600_000) return cached
        val webUrl = "$mbWebBase/wefeed-h5api-bff/ranking-list/content" +
            "?id=8614980535946986176&page=1&perPage=1"
        val tok = mbTokenFrom(
            webUrl, signed = false, clientInfo = null, ua = browserUA
        )
        if (tok != null) {
            mbCachedWebToken = tok
            mbCachedWebAt = now
        }
        return tok
    }

    /** Play via the WEB base with a WEB token and return the
     * caption candidates of THAT play response: (h5Caps,
     * streamIds, playCode). Playback itself is untouched
     * (mobile token, loadLinks). Why: the phone's mobile-token
     * play response carries stream ids of a different kind
     * that return EMPTY captions from the H5 caption endpoint
     * (v3 DEBUG phone id 7599736117126382536 -> 200-empty on
     * both bases x all formats, re-verified live v4 T25),
     * while WEB-play ids return 14 captions per MP4 id incl.
     * ar+en, 16 for DASH (v4 T26b, both bases). The web ids
     * are stable across calls (identical to T17) but are
     * always fetched live here — never hardcoded. */
    private suspend fun mbWebPlayCaps(
        subjectId: String,
        detailPath: String,
        se: Int,
        ep: Int,
        token: String
    ): Triple<List<Pair<String, String>>, List<String>, String> {
        var playCode = "?"
        for (base in listOf(mbWebBase, "https://movie-box.co")) {
            val seEp = if (se == 0 && ep == 0) {
                "detailSe=&detailEp="
            } else {
                "detailSe=$se&detailEp=$ep"
            }
            val referer = "$base/movies/$detailPath?id=$subjectId" +
                "&type=/movie/detail&$seEp&lang=en"
            val playUrl = "$base/wefeed-h5api-bff/subject/play" +
                "?subjectId=$subjectId&se=$se&ep=$ep" +
                "&detailPath=$detailPath" +
                "&streamSignType=1" +
                "&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
            val headers = mbSignedHeaders(
                "GET", playUrl, mbUaPlayChrome, mbClientInfoPlay()
            ) + mapOf(
                "Referer" to referer,
                "Authorization" to "Bearer $token",
                "X-MB-Token" to token,
                "x-play-mode" to "1",
                "x-request-lang" to "en",
                "x-vip-restrict" to "0",
                "x-no-high-risk-restrict" to "0",
                "x-source" to ""
            )
            val res = runCatching { app.get(playUrl, headers = headers) }
                .getOrNull() ?: continue
            playCode = res.code.toString()
            if (res.code !in 200..299 || res.text.isBlank()) continue
            val d = runCatching {
                JSONObject(res.text).optJSONObject("data")
            }.getOrNull() ?: continue
            val streamObjs = ArrayList<JSONObject>()
            for (key in listOf("streams", "dash", "hls")) {
                val arr = d.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { streamObjs += it }
                }
            }
            if (streamObjs.isEmpty()) continue
            val caps = ArrayList<Pair<String, String>>()
            val ids = ArrayList<String>()
            for (s in streamObjs) {
                val sid = s.optString("id")
                if (sid.isBlank()) continue
                val surl = mbStreamUrl(s) ?: continue
                val fmt = when {
                    surl.contains(".mpd") -> "DASH"
                    surl.contains(".m3u8") -> "HLS"
                    else -> "MP4"
                }
                if (caps.none { it.first == sid }) caps += sid to fmt
                if (!ids.contains(sid)) ids += sid
            }
            if (caps.isNotEmpty()) return Triple(caps, ids, playCode)
        }
        return Triple(emptyList(), emptyList(), playCode)
    }

    /** Captions for a title — UNION across every route, never
     * an early return on a partial hit. Routes, in order: H5
     * caption per stream id (moviebox.sg first — the verified
     * base — then the play base), mobile ext-captions, mobile
     * stream-captions. Stops early only once BOTH sides are
     * found. Returns the sub diagnostic: "ar+en" / "ar" / "en"
     * / "ar+mf", else a compact "none:<route>=<code>/..."
     * trail (with "/ids=<tried stream ids>" so the next phone
     * DEBUG shows the exact ids to test).
     * v4 — why the phone's ids fail: the phone's v3 DEBUG
     * showed 10x h5=200-empty with ITS OWN ids logged
     * (/ids=7599736117126382536,...), and that first phone id
     * returns 200-empty from the sandbox too, on both bases x
     * all formats (v4 T25) — while WEB-play ids return 14
     * captions per MP4 id incl. ar+en, 16 for DASH (v4 T26b).
     * So mobile-token play ids are a different kind that
     * carries NO captions at the H5 endpoint — not a URL /
     * header / format bug. The fix adds Phase B: when Phase A
     * (the play ids) does not find BOTH sides, mint a WEB
     * token, call play on the web base, and run the H5 route
     * with THOSE ids (trail "wh5=...", "/wids=<web ids>").
     * Playback itself is untouched (mobile token). Mobile
     * ext-captions 400 in v2/v3 because resourceId is null —
     * the field exists in NO response and IN's dex never reads
     * a "resourceId" JSON key — so v3 tries subjectId ONLY
     * (mob1), then resourceId := first stream id + episode=0
     * (mob2); the IN-shape runs only if a real resourceId
     * ever appears. Stream-captions (IN-exact:
     * subjectId + streamId only) tries ALL stream ids.
     * The CNC dex emits SubtitleFile with (lang, url) and NO
     * headers, so no headers are passed with the caption URL. */
    private suspend fun mbEmitCaptions(
        subjectId: String,
        detailPath: String,
        playBase: String,
        token: String,
        h5Caps: List<Pair<String, String>>,
        streamIds: List<String>,
        resourceId: String?,
        se: Int,
        ep: Int,
        t0: Long,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val trail = ArrayList<String>()
        val triedIds = ArrayList<String>()
        val webTriedIds = ArrayList<String>()
        var ara: String? = null
        var eng: String? = null
        fun absorb(arr: JSONArray?) {
            val (a, e) = mbPickCaptions(arr)
            if (ara == null) ara = a
            if (eng == null) eng = e
        }
        fun bothFound(): Boolean = ara != null && eng != null
        // One mobile caption GET: signed headers like every
        // other mobile call; records "tag=code" in the trail
        // and absorbs any captions array on 2xx.
        suspend fun tryMobile(url: String, tag: String) {
            runCatching {
                val headers = mbSignedHeaders(
                    "GET", url, mbUaToken, mbClientInfoToken()
                ) + mapOf(
                    "Authorization" to "Bearer $token",
                    "X-MB-Token" to token
                )
                val res = app.get(url, headers = headers)
                trail += "$tag=${res.code}"
                if (res.code in 200..299) {
                    val json = runCatching { JSONObject(res.text) }.getOrNull()
                    absorb(mbCaptionsArray(json, "extCaptions", "captions"))
                }
            }
        }
        // H5 caption pass over one (stream id, format) list:
        // moviebox.sg first, then the play base if different.
        // tag is the trail prefix ("h5" for the play ids,
        // "wh5" for the web-play ids).
        suspend fun h5Pass(
            caps: List<Pair<String, String>>,
            tag: String,
            tried: MutableList<String>
        ) {
            val h5Bases = listOf(mbWebBase, playBase).distinct()
            for ((sid, fmt) in caps) {
                if (bothFound()) break
                if (!tried.contains(sid)) tried += sid
                for (base in h5Bases) {
                    if (bothFound()) break
                    val capUrl = "$base/wefeed-h5api-bff/subject/caption" +
                        "?format=$fmt&id=$sid&subjectId=$subjectId" +
                        "&detailPath=$detailPath"
                    val headers = mbSignedHeaders(
                        "GET", capUrl, mbUaToken, mbClientInfoToken()
                    ) + mapOf(
                        "Authorization" to "Bearer $token",
                        "X-MB-Token" to token
                    )
                    val res = runCatching {
                        app.get(capUrl, headers = headers)
                    }.getOrNull()
                    val code = res?.code ?: 0
                    if (res == null || code !in 200..299) {
                        trail += "$tag=$code"
                        continue
                    }
                    val json = runCatching { JSONObject(res.text) }.getOrNull()
                    val arr = mbCaptionsArray(json, "captions", "extCaptions")
                    if (arr == null || arr.length() == 0) {
                        trail += "$tag=$code-empty"
                        continue
                    }
                    absorb(arr)
                    trail += "$tag=$code-ok"
                }
            }
        }
        // Mobile caption routes over one id list. prefix is
        // "" for the play ids ("mob1"/"mob2"/"mobstr") and "w"
        // for the web-play ids ("wmob2"/"wmobstr"). The
        // subjectId-only call is skipped on the web pass — it
        // is the same request as Phase A.
        suspend fun mobilePass(
            ids: List<String>,
            prefix: String,
            includeExtOnly: Boolean
        ) {
            if (!bothFound()) {
                if (!resourceId.isNullOrBlank()) {
                    tryMobile(
                        "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                            "get-ext-captions?subjectId=$subjectId" +
                            "&resourceId=$resourceId&episode=0",
                        prefix + "mob"
                    )
                } else {
                    if (includeExtOnly) {
                        tryMobile(
                            "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                                "get-ext-captions?subjectId=$subjectId",
                            prefix + "mob1"
                        )
                    }
                    val firstSid = ids.firstOrNull()
                    if (!bothFound() && !firstSid.isNullOrBlank()) {
                        tryMobile(
                            "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                                "get-ext-captions?subjectId=$subjectId" +
                                "&resourceId=$firstSid&episode=0",
                            prefix + "mob2"
                        )
                    }
                }
            }
            if (!bothFound()) {
                for (sid in ids) {
                    if (bothFound()) break
                    tryMobile(
                        "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                            "get-stream-captions?subjectId=$subjectId&streamId=$sid",
                        prefix + "mobstr"
                    )
                }
            }
        }
        // Phase A: the play response's own ids (mobile token).
        h5Pass(h5Caps, "h5", triedIds)
        mobilePass(streamIds, "", true)
        // Phase B: captions with WEB-play ids (v4). Skipped
        // once both sides are found, and skipped late (>20 s
        // into loadLinks) so a slow caption hunt never stalls
        // playback.
        if (!bothFound()) {
            if (System.currentTimeMillis() - t0 > 20000) {
                trail += "wskip=slow"
            } else {
                val webToken = mbWebToken()
                trail += "wtok=" + if (webToken != null) "web" else "none"
                if (webToken != null) {
                    val (webCaps, webIds, webCode) = mbWebPlayCaps(
                        subjectId, detailPath, se, ep, webToken
                    )
                    trail += "wplay=$webCode"
                    if (webCaps.isNotEmpty()) {
                        h5Pass(webCaps, "wh5", webTriedIds)
                        mobilePass(webIds, "w", false)
                    }
                }
            }
        }
        if (ara == null && eng == null) {
            var out = "none:" + trail.joinToString("/")
            if (triedIds.isNotEmpty()) out += "/ids=" + triedIds.joinToString(",")
            if (webTriedIds.isNotEmpty()) out += "/wids=" + webTriedIds.joinToString(",")
            return out
        }
        return mbEmitUnion(ara, eng, subtitleCallback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - t0 }
        val parts = data.split("|||")
        val realData = parts.getOrNull(0) ?: data
        val subjectId = parts.getOrNull(2).orEmpty()
        val se = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val ep = parts.getOrNull(4)?.toIntOrNull() ?: 0
        val detailPath = realData.substringAfter("/moviedetail/")
            .substringBefore("?").substringBefore("#").trim()
        var streamsFound = 0
        var emitted = 0
        var playCode = "?"
        var tokVia = "none"
        var resourceId: String? = null
        var subDiag = "?"
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        if (subjectId.isBlank() || detailPath.isBlank()) {
            emitDebug(
                listOf(
                    "MovieBoxIN v4", "srv=0", "ext=0",
                    "t=${elapsed() / 1000}s", "tok=$tokVia",
                    "play=$playCode", "id-missing"
                ),
                callback
            )
            return true
        }
        val (token, via) = mbGuestToken()
        tokVia = via
        if (token != null) {
            val bases = listOf("https://movie-box.co", "https://moviebox.sg")
            for (base in bases) {
                if (emitted > 0 || elapsed() > 20000) break
                // The play Referer is the FULL player-page URL on
                // the SAME base, with the detailPath in the path.
                // detailPath goes into the play URL RAW (no
                // URL-encoding). Movies play with se=0&ep=0 and
                // carry EMPTY detailSe/detailEp in the Referer
                // (the v17 shape proven on-device); episodes
                // carry their real se/ep in both places — the
                // same rule emitMovieBoxLink applies to the
                // emitted links, so both Referers always match.
                val seEp = if (se == 0 && ep == 0) {
                    "detailSe=&detailEp="
                } else {
                    "detailSe=$se&detailEp=$ep"
                }
                val referer = "$base/movies/$detailPath?id=$subjectId" +
                    "&type=/movie/detail&$seEp&lang=en"
                val playUrl = "$base/wefeed-h5api-bff/subject/play" +
                    "?subjectId=$subjectId&se=$se&ep=$ep" +
                    "&detailPath=$detailPath" +
                    "&streamSignType=1" +
                    "&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                val headers = mbSignedHeaders(
                    "GET", playUrl, mbUaPlayChrome, mbClientInfoPlay()
                ) + mapOf(
                    "Referer" to referer,
                    "Authorization" to "Bearer $token",
                    "X-MB-Token" to token,
                    "x-play-mode" to "1",
                    "x-request-lang" to "en",
                    "x-vip-restrict" to "0",
                    "x-no-high-risk-restrict" to "0",
                    "x-source" to ""
                )
                val res = runCatching { app.get(playUrl, headers = headers) }
                    .getOrNull() ?: continue
                playCode = res.code.toString()
                if (res.code !in 200..299) continue
                val body = res.text
                if (body.isBlank()) continue
                val d = runCatching {
                    JSONObject(body).optJSONObject("data")
                }.getOrNull() ?: continue
                resourceId = d.optString("resourceId").ifBlank { null }
                    ?: resourceId
                val streamObjs = ArrayList<JSONObject>()
                for (key in listOf("streams", "dash", "hls")) {
                    val arr = d.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { streamObjs += it }
                    }
                }
                streamsFound += streamObjs.size
                if (streamObjs.isNotEmpty()) {
                    if (resourceId == null) {
                        resourceId = streamObjs[0].optString("resourceId")
                            .ifBlank { null }
                    }
                }
                // H5 caption candidates — (stream id, format)
                // from the play response, first id per format.
                // v3: ALL stream ids (was: first 3) — the
                // phone's v2 DEBUG showed 6x h5=200-empty while
                // every id of S1E1 returns real captions live,
                // so the phone's play response must carry
                // different ids; widening the net costs one
                // fast call per id and the tried ids are logged
                // in the DEBUG trail.
                val h5Caps = ArrayList<Pair<String, String>>()
                for (s in streamObjs) {
                    val sid = s.optString("id")
                    if (sid.isBlank()) continue
                    val surl = mbStreamUrl(s) ?: continue
                    val fmt = when {
                        surl.contains(".mpd") -> "DASH"
                        surl.contains(".m3u8") -> "HLS"
                        else -> "MP4"
                    }
                    if (h5Caps.none { it.first == sid }) h5Caps += sid to fmt
                }
                for (s in streamObjs) {
                    if (emitted >= 6 || elapsed() > 25000) break
                    val linkUrl = mbStreamUrl(s) ?: continue
                    val signCookie = s.optString("signCookie")
                        .ifBlank { null }
                    val signHeaderKey = s.optString("signHeaderKey")
                        .ifBlank { null }
                    val resLabel = s.optString("resolution").ifBlank {
                        s.optJSONArray("resolutions")
                            ?.optJSONObject(0)?.optString("resolution")
                            .orEmpty()
                    }
                    val q = Regex("""(2160|1440|1080|720|480|360)""")
                        .find(resLabel)?.groupValues?.get(1)
                    val label = "MovieBox" + if (q != null) " ${q}p" else ""
                    emitMovieBoxLink(
                        linkUrl, label, signCookie, signHeaderKey,
                        base, detailPath, subjectId, se, ep, counting
                    )
                }
                if (emitted > 0) {
                    val streamIds = streamObjs.mapNotNull { o ->
                        o.optString("id").ifBlank { null }
                    }.distinct()
                    subDiag = mbEmitCaptions(
                        subjectId, detailPath, base, token, h5Caps,
                        streamIds, resourceId, se, ep, t0,
                        subtitleCallback
                    )
                    break
                }
            }
        }
        if (emitted == 0 || subDiag != "ar+en") {
            // A missing subtitle must never be silent again —
            // emit the DEBUG line even when playback links exist.
            emitDebug(
                listOf(
                    "MovieBoxIN v4", "srv=$streamsFound",
                    "ext=$emitted", "t=${elapsed() / 1000}s",
                    "tok=$tokVia", "play=$playCode",
                    "se=$se", "ep=$ep", "sub=$subDiag"
                ),
                callback
            )
        }
        return true
    }
}
