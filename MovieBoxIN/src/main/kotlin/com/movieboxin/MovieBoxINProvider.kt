package com.movieboxin

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
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
 * v5 (his direct order 2026-11-16 12:00 — «يا رجل، لا داعي
 * للتشخيص — عندك الإضافات، خذ منها وابدأ، أنا أعطيك الأمر» —
 * after v4 failed on his phone 09:31 «لا يعمل v4 نفس الوضع»):
 * the CNC IN copy is the LIMITED one; the OTHER MovieBox
 * addons (AllForU v96, Rennvol v9 — both studied from their
 * published files in ~/workspace/movieboxin-addon/candidates/)
 * search the whole site, bring subtitles correctly, and show
 * 14 sources for Prison Break vs our srv=5. What v5 takes
 * from them (proven live via the H5 chain, T45–T52):
 * - SEARCH is the paginated API now (rennvol's shape): the
 *   app calls search(query, page) and the base implementation
 *   wraps the legacy search(query) list with hasNext=false
 *   ALWAYS (MainAPI.kt:641-655) — that was the structural
 *   "limited search": one call, no further pages, ever. v5
 *   overrides search(query, page) itself, one route-page per
 *   call, hasNext from the route's pager, so the app pages
 *   through the whole catalog ("prison break" totalCount 128,
 *   "love" 452 — T48). The response-shape parser now also
 *   reads data.subjects + data.results[].subjects (rennvol
 *   subjectsOf, allforu parseSubjects: items first, then
 *   subjects) — the v4 parser missed "subjects", the mobile
 *   dex shape.
 * - SOURCES are ALL of them now (rennvol's 3-stage loadLinks,
 *   H5-ported): H5 detail gives data.subject.dubs[] (11 for
 *   PB, 6 for Other Mommy — the app's own picker, verbatim);
 *   v5 plays EVERY dub subject (main + Arabic sub + French
 *   dub + ...) and emits every stream, deduped by URL.
 *   Counted live (T46b): 30 distinct streams / 30 unique
 *   URLs for PB S1E1 (5+5+2+3+1+3+4+1+1+2+3) — more than
 *   the 14 the other addons show, and every one a real file.
 *   The mobile-only stages (resourceDetectors, resource list)
 *   have NO H5 twin (H5 detail: 0 detectors, T45/T49/T51;
 *   H5 subject/resource: 404, T47) so they are not ported —
 *   nothing is faked from them. Labels carry the dub name
 *   ("MovieBox Arabic sub 1080p") so the player shows which
 *   encode each source is.
 * - SUBTITLES gain Phase C: the first MP4 id of each dub
 *   (T52: French MP4 id -> 1 French caption; other dub ids
 *   -> 0; the Arabic-sub version's ids -> 0, T36). The ar+en
 *   union still comes from the main subject's web-play ids
 *   (Phase B, v4) — dub ids can only add a missing side.
 * - TOP100 is UNCHANGED (v4's identification stands: neither
 *   candidate has any TOP100/Top Series string at all).
 * Still NO trailer in v5.
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
 * and reopen. DEBUG ("MovieBoxIN v7 | ... | sub=...") is emitted
 * whenever links are zero OR the dual is missing, so a failure
 * is never silent.
 *
 * v6 (panel consensus 6/6, 2026-11-16 — v5 regressions on his
 * phone: weak search, incomplete sideways pages, Prison Break
 * vanished from search, TOP100 row gone entirely):
 * - SEARCH is H5 FIRST + mobile merge (evidence, not opinion:
 *   H5 "prison break" p1 has Prison Break S1-S5 FIRST, T53;
 *   mobile behaviour on the phone is unverified). Per page:
 *   H5 subject/search, then mobile search/v2, merged by
 *   subjectId (H5 set first, mobile adds only what H5 lacks).
 *   Fallback triggers on ZERO USABLE ITEMS — a 200 with an
 *   empty/wrong array is a failure, never a success. If both
 *   routes are empty, H5 page 1 is retried once; still empty
 *   -> empty + hasNext=false. The legacy search(query) is
 *   DELETED entirely (the base wraps it with hasNext=false
 *   always, so keeping it lets the app take a dead path).
 * - PARSER mbSubjectsArray is strict now: candidates in order
 *   items -> subjects -> subjectList -> list ->
 *   results[].subjects (results LAST, most ambiguous); the
 *   FIRST NON-EMPTY array whose entries parse as subjects wins
 *   — never the first EXISTING key. The chosen key + counts
 *   are logged (ms=, ms_key=, hs=, key=).
 * - PAGER is the only stop condition: hasNext = pager.hasMore
 *   when the pager exists; nextPage is a STRING ('2'), parsed
 *   as text (T53/T55/T57). The "full page >= 20" rule is NEVER
 *   used when a pager exists (H5 search pages are 10, ranking
 *   pages 16/7 — the rule killed paging after p1, proven T55);
 *   it is a cautious fallback ONLY when no pager exists, and
 *   it is logged. Page 1 never returns hasNext=false when
 *   totalCount > returned count.
 * - HOME never drops a section for an empty first page: every
 *   section stays visible with an empty/error state, each with
 *   its own page number + endpoint (no global page counter).
 *   getMainPage serves ONE page per call — no pre-fetching all
 *   pages (that truncated the sideways scroll). fetchRankPage
 *   walks the pager (while hasMore, cap 100 new cards / 10 API
 *   pages per call) so a page that brings zero NEW cards while
 *   hasMore=true is skipped instead of ending the row.
 * - TOP100 stays on the MOBILE tab/ranking-list route
 *   (fetchCatPage, categoryType 4741626294545400336 "Top Series
 *   This Week") + fallback + never-drop (HomePageList always
 *   added, hasNext=true while uncertain, lazy retry). The H5
 *   ranking-list/content route for this id is NOT a source:
 *   proven live it returns the Hindi-heavy 'Top Series' list
 *   (T55/T56 — lang/region params change nothing), zero
 *   overlap with his screenshots. categoryType
 *   4516404531735022304 is "Trending", NOT Top 100 — never a
 *   TOP100 source (it is only the token-mint URL in
 *   mbGuestToken). Fallback for an empty TOP100 page 1 is the
 *   mobile tab-operating "Must-watch Top 100" section's own
 *   subjects (the row's true first page). DEBUG top=
 *   (ok/empty/err:<code>) + count + route.
 * - DEBUG is extended (ms=, ms_key=, hs=, pg=, hm=, nx=,
 *   top=, tot=, pages=, src=h5|mobile|merged, key=) and the
 *   last search + TOP100 diagnostics ride on the loadLinks
 *   DEBUG line, so they are visible on the phone.
 * Still NO trailer in v6. Still no coroutines anywhere.
 *
 * v7 (merge panel consensus 6/6, 2026-11-16 — his order: take
 * another repo's addon and merge it on; v6 is the BASE for
 * every feature, the donors add pieces only; CNC rejected by
 * all 6):
 * - H5 HOST POOL (Rennvol/AllForU shape, H5 FAILOVER ONLY):
 *   primary moviebox.sg first; failover h5-api.aoneroom.com
 *   (AllForU's H5_API), verified live as an exact H5 twin
 *   (T59–T63: search/ranking/detail/caption/tab-operating all
 *   200, identical payloads). Failover only on non-200 /
 *   exception / empty body; DEBUG host= shows which host
 *   served the call (hhost= for search). No region-spoofing
 *   header anywhere.
 * - SUBTITLES: Rennvol's ext-captions as an EXTRA Stage A
 *   fallback — get-ext-captions?resourceId=<stream id> (no
 *   episode) then &episode=<real ep> — fires ONLY when Stage
 *   A left a language empty; dedupe by language code before
 *   the dual build (same file for both sides kept once).
 *   The endpoint is MovieBox's own mobile gateway (same host
 *   as Stage A); the files come from MovieBox's delivery CDN
 *   (cacdn.hakunaymatata.com, same as the H5 caption files).
 *   Rennvol stages 1–2 NOT ported (no H5 twin: 404 / 0).
 * - MOBILE AUTH (Rennvol, behind mbRennvolMobileAuth): the
 *   x-user guest token is absorbed from every mobile
 *   response, not only at minting. H5 calls never go through
 *   it. The HMAC-MD5 signing + guest JWT were already v6's
 *   (same keys/canonical as Rennvol's).
 * - TOP100: on a failed fetch the row carries ONE diagnostic
 *   card (source + status in its title) instead of an empty
 *   row. Row names are deduped at the response site
 *   (mbUniqueRows). DEBUG adds dup=/drop=/nourl= (emission
 *   accounting), pg= (fetchRankPage final page, never above
 *   100 — the loop breaks with err:pgcap past it),
 *   hdr=OK (no prohibited header anywhere, test-enforced),
 *   top=INJ (the TOP100 row was injected into a home
 *   response this session).
 * Still NO trailer in v7. Still no coroutines anywhere.
 * The 30 H5 streams for S1E1 stand (the other addons' 14 is
 * their filtered subset, not ground truth).
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

    /** First-page cards of the mobile tab-operating "Must-watch
     * Top 100" section (v6): the fixed cat: row's fallback when
     * the mobile tab/ranking-list feed is empty on page 1.
     * Kept OUT of mbHomeSections on purpose — the cat: entry
     * owns that row, and a second entry with the same title
     * would overwrite it in the app's expandable map (the app
     * keys rows by name, HomeViewModel initial load). */
    @Volatile
    private var mbTop100TabCards: List<SearchResponse> = emptyList()

    /** One parsed list-endpoint page (v6 — the pager contract):
     * items, hasMore from the pager (never from page size while
     * a pager exists), nextPage as a STRING ('2'), totalCount,
     * the array key that won, and a short status for DEBUG. */
    private data class MbPage(
        val items: List<SearchResponse>,
        val hasMore: Boolean,
        val nextPage: String?,
        val totalCount: Int?,
        val key: String,
        val status: String
    )

    /** A parsed subject with its own id (v6 — search merge
     * dedups by subjectId, never by URL: two records can share
     * a URL in the mobile shape). Falls back to detailPath when
     * the record carries no subjectId. */
    private data class MbSubjectHit(val id: String, val item: SearchResponse)

    /** The pager object of a list response (v6): hasMore /
     * nextPage / totalCount, each nullable when absent. nextPage
     * is a STRING on this API ('2'), never an Int. */
    private data class MbPager(
        val hasMore: Boolean?,
        val nextPage: String?,
        val totalCount: Int?
    )

    /** Last search + TOP100 diagnostics (v6 — extended DEBUG).
     * The home/search paths have no phone-visible surface of
     * their own, so the latest lines ride on the loadLinks
     * DEBUG line (see loadLinks) and are readable on the phone
     * whenever an episode is opened. */
    @Volatile
    private var mbLastSearchDebug: String = ""
    @Volatile
    private var mbLastTopDebug: String = ""
    /** Last H5 pool host that served a call (v7): "web" for
     * the primary moviebox.sg, "h5api" for the failover — the
     * DEBUG line shows it, so a silent failover never masks
     * a broken primary. */
    @Volatile
    private var mbLastH5Host: String = "-"
    /** fetchRankPage while-loop final page (v7 — pg= guard:
     * the loop never requests a page above 100). */
    @Volatile
    private var mbLastRankPg: Int = 0
    /** TOP100 row injected into a home response this session
     * (v7 — top=INJ flag on the DEBUG line). */
    @Volatile
    private var mbTop100Injected: Boolean = false
    /** Rennvol mobile-auth flag (v7): the x-user guest-token
     * absorption on mobile responses runs only while this is
     * true. H5 calls never go through it — they are
     * unaffected by construction. Phone-only test (the
     * sandbox 403s on the mobile gateway). */
    private val mbRennvolMobileAuth = true

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
    /** H5 host pool (v7 — Rennvol/AllForU shape, H5 FAILOVER
     * ONLY): primary moviebox.sg first; the failover host is
     * AllForU's H5_API, verified live as an exact H5 twin
     * (T59–T63: search / ranking-list / detail / caption /
     * tab-operating all 200 with identical payloads). A
     * failover host is tried ONLY on a non-200 / exception /
     * empty body from the primary — never pre-emptively,
     * never for mobile calls, never with any region header. */
    private val mbH5FailoverBase = "https://h5-api.aoneroom.com"
    private val mbH5Bases = listOf(mbWebBase, mbH5FailoverBase)
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
            mbAbsorbToken(res.headers["x-user"] ?: res.headers["X-User"])
            if (res.code !in 200..299) return@runCatching null
            JSONObject(res.text).optJSONObject("data")
                ?.optJSONArray("sections")
        }.getOrNull()
    }

    /** H5 tab-operating twin (moviebox.sg, no signing, no token).
     * Verified live 200 from the sandbox, 2026-11-16: 36 sections,
     * 383 subjects. v7: through the H5 pool (failover
     * h5-api.aoneroom.com, T63 — same 36 sections live).
     * Returns the operatingList array, or null. */
    private suspend fun fetchH5Tabs(): JSONArray? {
        return runCatching {
            val body = mbH5Get(
                "/wefeed-h5api-bff/tab-operating?page=1&tabId=0",
                mbJsonHeaders
            )?.first ?: return@runCatching null
            JSONObject(body).optJSONObject("data")
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
    /** Cross-page search dedup (v7 — Gemma guard): subjectIds
     * already shown for the current query, so a boundary card
     * the live API repeats on the next page is never shown
     * twice. Reset on page 1 or a new query. */
    private val mbSearchSeenLock = Any()
    private var mbSearchSeenQuery: String = ""
    private val mbSearchSeenIds = HashSet<String>()

    /** Tab-operating sections as home records (mobile first, H5
     * twin fallback). Titles deduped (first non-empty wins —
     * the app keys rows by title); empty sections are KEPT
     * (v6 — a row is never dropped for an empty first page).
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
            val title = section.optString("title").ifBlank { "✨Top Picks" }
            // v6 — the mobile tab-operating "Must-watch Top 100"
            // section is NOT a home row of its own: the fixed
            // cat: entry owns that row (a second entry with the
            // same title would overwrite it in the app's
            // expandable map). Its subjects are kept aside as
            // the cat: feed's page-1 fallback instead.
            if (title.trim() == mbTop100Title ||
                (title.contains("Must-watch") && title.contains("Top 100"))) {
                if (cards.isNotEmpty()) mbTop100TabCards = cards
                continue
            }
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
     * live: Horror Movies pp.1-3 = 17/15/18 new cards, Popular
     * Series 16/15/13, zero overlap, hasMore true throughout;
     * T57 re-verified 2026-11-16: subjectList, hasMore=true,
     * nextPage STRING '2', totalCount=0).
     * v6 — the pager contract: hasMore comes ONLY from
     * pager.hasMore when it exists (never from page size — H5
     * ranking pages are 16/7, so the old >=20 rule ended the
     * row after page 1, proven T55). The call walks the pager
     * (while hasMore, cap 100 NEW cards / 10 API pages): a page
     * that brings zero NEW cards while hasMore=true is SKIPPED
     * instead of ending the row (the v5 bug: such a page
     * returned hasNext=false). Starts at the requested page —
     * one app page per call, no pre-fetching all pages. */
    private suspend fun fetchRankPage(
        genreTopId: String,
        page: Int
    ): MbPage {
        if (genreTopId.isBlank() || page < 1) {
            return MbPage(emptyList(), false, null, null, "none", "err:bad-arg")
        }
        val seen = synchronized(mbRankSeenLock) {
            mbRankSeen.getOrPut(genreTopId) { HashSet() }.also {
                if (page <= 1) it.clear()
            }
        }
        val out = ArrayList<SearchResponse>()
        var cur = page
        var hasMore = false
        var nextPage: String? = null
        var totalCount: Int? = null
        var key = "none"
        var status = "empty"
        var attempts = 0
        while (attempts < 10 && out.size < 100) {
            // v7 — pg= guard (Gemma's search-loop risk): the
            // loop never requests a page above 100, even if
            // the API keeps reporting hasMore=true forever.
            if (cur > 100) {
                status = "err:pgcap"
                break
            }
            attempts++
            val got = mbH5Get(
                "/wefeed-h5api-bff/ranking-list/content" +
                    "?id=$genreTopId&page=$cur&perPage=20",
                mbJsonHeaders
            )
            if (got == null) {
                status = "err:net"
                break
            }
            val json = runCatching { JSONObject(got.first) }.getOrNull()
            if (json == null) {
                status = "err:parse"
                break
            }
            val data = json.optJSONObject("data")
            if (data == null) {
                status = "err:nodata"
                break
            }
            val picked = mbSubjectsArray(data)
            val pager = mbPager(data)
            // Cautious fallback ONLY when the pager carries no
            // hasMore at all (logged as "/full" in status).
            val fullFallback = pager.hasMore == null
            hasMore = pager.hasMore
                ?: ((picked?.first?.length() ?: 0) >= 20)
            nextPage = pager.nextPage
            totalCount = pager.totalCount
            key = picked?.second ?: "none"
            val arr = picked?.first
            if (arr != null) {
                synchronized(mbRankSeenLock) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val subj = o.optJSONObject("subject") ?: o
                        val dp = subj.optString("detailPath")
                        if (dp.isBlank() || !seen.add(dp)) continue
                        parseSubject(subj)?.let { out += it }
                    }
                }
            }
            status = (if (out.isNotEmpty()) "ok" else "empty") +
                if (fullFallback) "/full" else ""
            // One non-empty page per call (lazy paging); an
            // empty page with more behind it is skipped.
            if (out.isNotEmpty()) break
            if (!hasMore) break
            cur = nextPage?.toIntOrNull() ?: (cur + 1)
        }
        mbLastRankPg = minOf(cur, 100)
        return MbPage(out, hasMore, nextPage, totalCount, key, status)
    }

    /** One page of the mobile tab/ranking-list feed (v4 TOP100,
     * v6 rewritten). The dex's only ranking call: GET {mobile
     * base}/wefeed-mobile-bff/tab/ranking-list?tabId=0&
     * categoryType=<ct>&page=<page>&perPage=20, signed headers
     * (IN's shape) + the guest token. apig first, api3
     * fallback — both are mobile hosts; the phone reaches them
     * (tok=mob proves the token mint works there), the sandbox
     * gets 403 on both. Response items: the strict
     * mbSubjectsArray (data.subjects[] in the dex shape, with
     * the other keys as fallbacks); the pager is read with the
     * v6 contract (hasMore from the pager only, nextPage as a
     * STRING). Dedup by detailPath in a per-feed seen-set
     * (cleared on page 1).
     * v6 fallbacks — a 403 / non-200 / empty first page does
     * NOT end the row:
     * - any categoryType EXCEPT the TOP100 id: the H5
     *   ranking-list/content twin for the same id.
     * - the TOP100 id itself: NEVER the H5 ranking-list route
     *   (proven live it returns the Hindi-heavy 'Top Series'
     *   list, T55/T56 — a wrong list, not the app's TOP100);
     *   instead the mobile tab-operating "Must-watch Top 100"
     *   section's own subjects (the row's true first page) on
     *   page 1, and an empty retryable page after that.
     * The row is NEVER dropped by the caller: this returns the
     * status (ok/empty/err:<code>) + route so the DEBUG line
     * can show top= and the row stays expandable. */
    private suspend fun fetchCatPage(
        categoryType: String,
        page: Int
    ): MbPage {
        if (categoryType.isBlank() || page < 1 || page > 10) {
            return MbPage(emptyList(), false, null, null, "none", "err:bad-arg")
        }
        val isTop100 = categoryType == mbTop100CategoryType
        val (token, _) = mbGuestToken()
        val bases = listOf(mbMobileBase, mbApi3Base)
        var lastCode = "?"
        var mobileEmpty: MbPage? = null
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
                val r = app.get(url, headers = headers)
                mbAbsorbToken(r.headers["x-user"] ?: r.headers["X-User"])
                r
            }.getOrNull() ?: continue
            lastCode = res.code.toString()
            if (res.code !in 200..299) continue
            val json = runCatching { JSONObject(res.text) }.getOrNull()
                ?: continue
            val data = json.optJSONObject("data") ?: continue
            val picked = mbSubjectsArray(data) ?: continue
            val pager = mbPager(data)
            // Cautious fallback ONLY when the pager carries no
            // hasMore at all (logged as "/full" in status).
            val fullFallback = pager.hasMore == null
            val hasMore = pager.hasMore
                ?: (picked.first.length() >= 20)
            val key = "cat:$categoryType"
            val seen = synchronized(mbRankSeenLock) {
                mbRankSeen.getOrPut(key) { HashSet() }.also {
                    if (page <= 1) it.clear()
                }
            }
            val out = ArrayList<SearchResponse>()
            synchronized(mbRankSeenLock) {
                for (i in 0 until picked.first.length()) {
                    val o = picked.first.optJSONObject(i) ?: continue
                    val subj = o.optJSONObject("subject") ?: o
                    val dp = subj.optString("detailPath")
                    if (dp.isBlank() || !seen.add(dp)) continue
                    parseSubject(subj)?.let { out += it }
                }
            }
            val status = (if (out.isNotEmpty()) "ok" else "empty") +
                if (fullFallback) "/full" else ""
            val result = MbPage(
                out, hasMore, pager.nextPage, pager.totalCount,
                picked.second, status
            )
            if (out.isNotEmpty()) {
                updateTopDebug(result, "mobile")
                return result
            }
            if (mobileEmpty == null) mobileEmpty = result
        }
        // --- fallback (mobile feed 403 / empty on both bases) ---
        if (isTop100) {
            // NEVER the H5 ranking-list route for this id (T55:
            // 'Top Series' Hindi list — a wrong list, not the
            // app's TOP100). The row's true first page lives in
            // the mobile tab-operating section itself.
            if (page <= 1) {
                if (mbTop100TabCards.isEmpty()) {
                    runCatching { fetchTabSections() }
                }
                val cards = mbTop100TabCards
                if (cards.isNotEmpty()) {
                    val result = MbPage(
                        cards, true, "2", null, "tabs", "ok"
                    )
                    updateTopDebug(result, "tabs")
                    return result
                }
            }
            val result = MbPage(
                emptyList(), true, null, null, "none",
                "err:$lastCode"
            )
            updateTopDebug(result, "mobile")
            return result
        }
        // Generic cat: sections: the H5 ranking-list twin.
        val h5 = fetchRankPage(categoryType, page)
        val result = MbPage(
            h5.items, h5.hasMore, h5.nextPage, h5.totalCount,
            h5.key, h5.status
        )
        updateTopDebug(result, "h5")
        return result
    }

    /** Record the latest TOP100 fetch for the extended DEBUG
     * line (v6): top= (ok/empty/err:<code>) + count + route. */
    private fun updateTopDebug(page: MbPage, route: String) {
        mbLastTopDebug = "top=${page.status} n=${page.items.size} " +
            "route=$route key=${page.key} hm=${page.hasMore} " +
            "nx=${page.nextPage ?: "-"}"
    }

    /** One diagnostic card for a failed TOP100 fetch (v7 —
     * Haiku: a visible failure beats a missing row). Shown
     * ONLY when the fetch failed (status "err:<code>"), never
     * for an empty-but-retryable page. The title carries the
     * source + status; tapping it lands on the harmless
     * movieFallback page (no detailPath, no playback). The
     * URL carries the page number so one page's card never
     * dedups against another's. */
    private fun mbTop100DiagCard(page: Int, status: String, route: String): SearchResponse {
        val code = status.removePrefix("err:")
        val title = "⚠ TOP100 غير متاحة — المصدر: $route، الحالة: $code"
        return newMovieSearchResponse(
            title, "mbdiag://top100/p$page/err-$code", TvType.Movie
        )
    }

    /** TOP100 display items (v7): the real cards, or the one
     * diagnostic card when the fetch failed. */
    private fun mbTop100Items(page: MbPage, num: Int): List<SearchResponse> {
        if (page.items.isEmpty() && page.status.startsWith("err:")) {
            return listOf(mbTop100DiagCard(num, page.status, "mobile"))
        }
        return page.items
    }

    /** First occurrence per row title wins (v7 — the
     * HomeViewModel collision: a later same-named row
     * silently replaces the earlier one). fetchTabSections
     * already dedups titles; this is the belt-and-braces
     * guarantee at the response site. */
    private fun mbUniqueRows(lists: List<HomePageList>): List<HomePageList> {
        val seen = HashSet<String>()
        return lists.filter { seen.add(it.name) }
    }

    /** A rank section's first page (v6): its tab-operating
     * subjects when they exist, else its ranking-list page 1.
     * The tabs branch and the rank: branch MUST agree here —
     * the app overwrites same-named rows by response order, so
     * a disagreement would silently drop cards. */
    private suspend fun mbRankFirstPage(s: MbHomeSection): List<SearchResponse> {
        if (s.subjects.isNotEmpty()) return s.subjects
        if (s.genreTopId.isBlank()) return emptyList()
        return fetchRankPage(s.genreTopId, 1).items
    }

    /** The "tabs" request (v6). Page 1 refreshes the tab cache
     * and returns every section's first page — NO section is
     * ever dropped for an empty first page (an empty rank
     * section is filled from its ranking-list page 1, and the
     * TOP100 row is ALWAYS added, even empty, with its own
     * cat: entry owning the retry). Page>1 is the session-1
     * expansion fallback — the frozen mainPage has no
     * per-section entries yet, so the app re-calls "tabs":
     * return ONE page per section (never all pages). */
    private suspend fun getMainPageTabs(page: Int): HomePageResponse {
        if (page <= 1) {
            val sections = fetchTabSections()
            val lists = ArrayList<HomePageList>()
            // TOP100 first page — ALWAYS added, even empty
            // (never drop the row; its cat: entry owns the
            // retry and the hasNext).
            val top = fetchCatPage(mbTop100CategoryType, 1)
            lists += HomePageList(
                mbTop100Title, mbTop100Items(top, 1),
                isHorizontalImages = true
            )
            mbTop100Injected = true
            for (s in sections) {
                lists += HomePageList(
                    s.title, mbRankFirstPage(s), isHorizontalImages = true
                )
            }
            // Response-level hasNext is copied by the app into
            // EVERY row of this response, so it stays true
            // while any row can expand; each row's own branch
            // sets the per-row truth on the next load.
            return newHomePageResponse(mbUniqueRows(lists), true)
        }
        var anyMore = false
        val lists = ArrayList<HomePageList>()
        val top = fetchCatPage(mbTop100CategoryType, page)
        if (top.hasMore) anyMore = true
        lists += HomePageList(
            mbTop100Title, mbTop100Items(top, page),
            isHorizontalImages = true
        )
        mbTop100Injected = true
        for (s in mbHomeSections) {
            if (s.genreTopId.isBlank()) continue
            val rank = fetchRankPage(s.genreTopId, page)
            if (rank.hasMore) anyMore = true
            lists += HomePageList(
                s.title, rank.items, isHorizontalImages = true
            )
        }
        return newHomePageResponse(mbUniqueRows(lists), anyMore)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when {
            request.data == "tabs" -> getMainPageTabs(page)
            request.data.startsWith("rank:") -> {
                // v6: page 1 is the tab-operating first page
                // (same content as the tabs branch — the app
                // overwrites same-named rows by response order,
                // so the two MUST agree); pages 2+ walk the
                // ranking-list pager. The row is ALWAYS
                // returned, even empty.
                val gid = request.data.removePrefix("rank:")
                if (gid.isBlank()) {
                    newHomePageResponse(
                        listOf(
                            HomePageList(
                                request.name, emptyList(),
                                isHorizontalImages = true
                            )
                        ),
                        false
                    )
                } else if (page <= 1) {
                    var section = mbHomeSections.firstOrNull {
                        it.genreTopId == gid
                    }
                    if (section == null) {
                        fetchTabSections()
                        section = mbHomeSections.firstOrNull {
                            it.genreTopId == gid
                        }
                    }
                    val cards = if (section != null) {
                        mbRankFirstPage(section)
                    } else {
                        fetchRankPage(gid, 1).items
                    }
                    newHomePageResponse(
                        listOf(
                            HomePageList(
                                request.name, cards,
                                isHorizontalImages = true
                            )
                        ),
                        true
                    )
                } else {
                    val rank = fetchRankPage(gid, page)
                    newHomePageResponse(
                        listOf(
                            HomePageList(
                                request.name, rank.items,
                                isHorizontalImages = true
                            )
                        ),
                        rank.hasMore
                    )
                }
            }
            request.data.startsWith("cat:") -> {
                // TOP100 (v4 source, v6 contract): the mobile
                // tab/ranking-list feed. The row is ALWAYS
                // returned, even empty (never drop); hasNext
                // stays true while the outcome is uncertain so
                // a sideways scroll retries the feed — it is
                // false only for a proven-complete non-empty
                // list (pager says no more pages).
                val ct = request.data.removePrefix("cat:")
                if (ct.isBlank()) {
                    newHomePageResponse(
                        listOf(
                            HomePageList(
                                request.name, emptyList(),
                                isHorizontalImages = true
                            )
                        ),
                        false
                    )
                } else {
                    val cat = fetchCatPage(ct, page)
                    val hasNext = !(cat.items.isNotEmpty() && !cat.hasMore)
                    val items = if (ct == mbTop100CategoryType) {
                        mbTop100Injected = true
                        mbTop100Items(cat, page)
                    } else cat.items
                    newHomePageResponse(
                        listOf(
                            HomePageList(
                                request.name, items,
                                isHorizontalImages = true
                            )
                        ),
                        hasNext
                    )
                }
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
    /** The pager object of a list response (v6): hasMore /
     * nextPage / totalCount, each nullable when absent.
     * nextPage is a STRING on this API ('2'), never an Int. */
    private fun mbPager(data: JSONObject): MbPager {
        val p = data.optJSONObject("pager")
            ?: return MbPager(null, null, null)
        val hm = when (val v = p.opt("hasMore")) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> null
        }
        val nx = when (val v = p.opt("nextPage")) {
            is String -> v.ifBlank { null }
            is Number -> v.toString()
            else -> null
        }
        val tot = when (val v = p.opt("totalCount")) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
        return MbPager(hm, nx, tot)
    }

    /** Does this array hold at least one entry that parses as
     * a subject (non-blank detailPath + title, unwrapping a
     * "subject" wrapper when present)? */
    private fun mbParsesAsSubjects(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val subj = o.optJSONObject("subject") ?: o
            if (subj.optString("detailPath").isNotBlank() &&
                subj.optString("title").isNotBlank()) return true
        }
        return false
    }

    /** Strict response-shape parser (v6 — panel consensus):
     * candidates in order items -> subjects -> subjectList ->
     * list -> results[].subjects (results LAST — most
     * ambiguous, it may hold suggestions, not hits). The
     * FIRST NON-EMPTY array whose entries parse as subjects
     * wins — never the first EXISTING key (the v5 bug: a 200
     * with an empty/wrong first array stopped the fallback).
     * Returns the array + the key that won, or null. */
    private fun mbSubjectsArray(data: JSONObject): Pair<JSONArray, String>? {
        for (key in listOf("items", "subjects", "subjectList", "list")) {
            val arr = data.optJSONArray(key) ?: continue
            if (mbParsesAsSubjects(arr)) return arr to key
        }
        val results = data.optJSONArray("results") ?: return null
        val merged = JSONArray()
        for (i in 0 until results.length()) {
            val subs = results.optJSONObject(i)
                ?.optJSONArray("subjects") ?: continue
            for (j in 0 until subs.length()) {
                subs.optJSONObject(j)?.let { merged.put(it) }
            }
        }
        return if (mbParsesAsSubjects(merged)) {
            merged to "results[].subjects"
        } else null
    }

    /** One subject JSON object -> a hit carrying its own id
     * (v6 — merge dedup is by subjectId). */
    private fun mbParseHit(o: JSONObject?): MbSubjectHit? {
        if (o == null) return null
        val subj = o.optJSONObject("subject") ?: o
        val item = parseSubject(subj) ?: return null
        val sid = subj.optString("subjectId")
            .ifBlank { subj.optString("detailPath") }
        return MbSubjectHit(sid, item)
    }

    /** hasNext for one search route page (v6 — the pager
     * contract): pager.hasMore when present; never the
     * "full page >= 20" rule while a pager exists; page 1
     * never ends when totalCount > returned count; the full
     * page rule is a cautious fallback ONLY with no pager at
     * all. */
    private fun mbSearchHasNext(
        pager: MbPager,
        page: Int,
        count: Int
    ): Boolean {
        pager.hasMore?.let { return it }
        if (page == 1 && pager.totalCount != null) {
            return pager.totalCount > count
        }
        return count >= 20
    }

    /** One search-route page (v6): hits with subjectIds, the
     * pager, the array key that won, and the HTTP code. v7
     * adds the serving host ("web"/"h5api"/"mob") so the DEBUG
     * line shows which pool host answered. */
    private data class MbSearchPage(
        val hits: List<MbSubjectHit>,
        val pager: MbPager,
        val key: String,
        val code: String,
        val host: String = "-"
    )

    /** One page of one search route (v6): mobile search/v2
     * when mobile=true, H5 subject/search otherwise. Both
     * routes need the guest token (H5 without one is a 400
     * "invalid token", proven live). Returns the hits with
     * their subjectIds, the pager, the array key that won,
     * and the HTTP code (or "net"/"parse" on failure) — so
     * the caller can tell an empty page from a failed one. */
    private suspend fun mbSearchPage(
        query: String,
        page: Int,
        mobile: Boolean
    ): MbSearchPage {
        val empty = MbSearchPage(
            emptyList(), MbPager(null, null, null), "none", "?"
        )
        return runCatching {
            val (token, _) = mbGuestToken()
            val body = JSONObject().apply {
                put("page", page)
                put("perPage", 20)
                put("keyword", query)
            }.toString()
            // v7 — the H5 route goes through the pool (primary
            // first, failover only on non-200 / empty); the
            // mobile route keeps its single base + signed
            // headers. Both return (text, code, host) so the
            // caller can tell an empty page from a failed one.
            val text: String?
            val code: String
            val host: String
            if (mobile) {
                val url = "$mbApi3Base/wefeed-mobile-bff/subject-api/search/v2"
                val headers = mbSignedHeaders(
                    "POST", url, mbUaToken, mbClientInfoToken(),
                    body = body, contentType = "application/json; charset=utf-8"
                ).toMutableMap()
                if (!token.isNullOrBlank()) {
                    headers["Authorization"] = "Bearer $token"
                    headers["X-MB-Token"] = token
                }
                val res = app.post(
                    url, headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
                mbAbsorbToken(res.headers["x-user"] ?: res.headers["X-User"])
                code = res.code.toString()
                host = "mob"
                text = if (res.code in 200..299) res.text else null
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
                val (t, h, c) = mbH5Post(
                    "/wefeed-h5api-bff/subject/search", body, headers
                )
                text = t
                host = h
                code = c
            }
            if (text == null) {
                return empty.copy(code = code)
            }
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return empty.copy(code = "parse")
            val data = json.optJSONObject("data") ?: json
            val picked = mbSubjectsArray(data)
            val pager = mbPager(data)
            val hits = ArrayList<MbSubjectHit>()
            val arr = picked?.first
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    mbParseHit(arr.optJSONObject(i))?.let { hits += it }
                }
            }
            MbSearchPage(
                hits, pager, picked?.second ?: "none",
                code, host
            )
        }.getOrDefault(empty.copy(code = "net"))
    }

    /** Paginated search (v6 — panel consensus, H5 FIRST +
     * mobile merge). The app calls THIS: the base wraps the
     * legacy search(query) with hasNext=false ALWAYS
     * (MainAPI.kt:641-655), so v1–v4 search could never page.
     * Per page: H5 subject/search first (sandbox-proven —
     * "prison break" p1 has Prison Break S1-S5 FIRST, T53),
     * then mobile search/v2 for the same page, merged by
     * subjectId (H5 set first, mobile adds only what H5
     * lacks). v7 — cards are deduped by subjectId ACROSS
     * pages for one query too (the live API repeats a
     * boundary card: 'EWON (PRISON)' on p2 AND p3, T65).
     * A 200 with zero usable items is a FAILURE, not
     * a success — the other route always runs. If both routes
     * are empty, H5 page 1 is retried once; still empty ->
     * empty + hasNext=false. hasNext comes from the pagers
     * (either route having more pages means more).
     * NOTE: the legacy search(query) is DELIBERATELY not
     * overridden in v6 (deleted per the panel consensus) —
     * the base wraps it with hasNext=false always, and the
     * app only calls the paginated form (APIRepository.search
     * -> api.search(query, page), verified in cs-src). */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {
        if (query.isBlank() || page < 1) {
            return emptyList<SearchResponse>().toNewSearchResponseList(false)
        }
        val h5 = mbSearchPage(query, page, mobile = false)
        val mob = mbSearchPage(query, page, mobile = true)
        val seenIds = HashSet<String>()
        val merged = ArrayList<Pair<String, SearchResponse>>()
        for (h in h5.hits + mob.hits) {
            if (!seenIds.add(h.id)) continue
            merged += h.id to h.item
        }
        // v7 — cross-page dedup (Gemma's search-loop guard):
        // the live API can repeat a boundary card on the next
        // page (proven live: 'EWON (PRISON)' on p2 AND p3).
        // Cards already shown for this query are filtered from
        // later pages; page 1 (or a new query) resets the set.
        fun filterSeen(pairs: List<Pair<String, SearchResponse>>): List<SearchResponse> {
            synchronized(mbSearchSeenLock) {
                if (page <= 1 || query != mbSearchSeenQuery) {
                    mbSearchSeenQuery = query
                    mbSearchSeenIds.clear()
                }
                val kept = ArrayList<SearchResponse>()
                for ((id, item) in pairs) {
                    if (mbSearchSeenIds.add(id)) kept += item
                }
                return kept
            }
        }
        var out = filterSeen(merged)
        var src = when {
            h5.hits.isNotEmpty() && mob.hits.isNotEmpty() -> "merged"
            h5.hits.isNotEmpty() -> "h5"
            mob.hits.isNotEmpty() -> "mobile"
            else -> "none"
        }
        var h5More = mbSearchHasNext(h5.pager, page, h5.hits.size)
        val mobMore = mbSearchHasNext(mob.pager, page, mob.hits.size)
        if (out.isEmpty()) {
            // Both routes empty: one retry of H5 page 1, then
            // empty + hasNext=false. (A retry of the same page
            // is cheap and covers transient failures.)
            val retry = mbSearchPage(query, 1, mobile = false)
            val retryPairs = ArrayList<Pair<String, SearchResponse>>()
            val rSeen = HashSet<String>()
            for (h in retry.hits) {
                if (!rSeen.add(h.id)) continue
                retryPairs += h.id to h.item
            }
            out = filterSeen(retryPairs)
            if (retry.hits.isNotEmpty()) {
                src = "h5-retry"
                h5More = mbSearchHasNext(retry.pager, 1, retry.hits.size)
            }
        }
        var hasMore = out.isNotEmpty() && (h5More || mobMore)
        // Never end page 1 while the H5 catalog says more.
        val h5Tot = h5.pager.totalCount
        if (page == 1 && h5Tot != null && h5Tot > out.size) hasMore = true
        mbLastSearchDebug = "src=$src key=${h5.key}/${mob.key} " +
            "ms=${mob.hits.size} ms_key=${mob.key} hs=${h5.hits.size} " +
            "hhost=${h5.host} pg=$page hm=$hasMore " +
            "nx=${h5.pager.nextPage ?: mob.pager.nextPage ?: "-"} " +
            "tot=${h5Tot ?: mob.pager.totalCount ?: "-"} pages=$page " +
            "shown=${out.size}"
        return out.toNewSearchResponseList(hasMore)
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
            mbAbsorbToken(res.headers["x-user"] ?: res.headers["X-User"])
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
            mbH5Get(
                "/wefeed-h5api-bff/detail?detailPath=" +
                    java.net.URLEncoder.encode(dp, "UTF-8"),
                mbJsonHeaders
            )?.first
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

    /** H5 GET through the pool (v7): primary first, failover
     * only on a non-200 / exception / empty body. Returns
     * (body, hostLabel) or null; records the serving host in
     * mbLastH5Host. No region headers, no mobile hosts. */
    private suspend fun mbH5Get(
        path: String,
        headers: Map<String, String>
    ): Pair<String, String>? {
        for (base in mbH5Bases) {
            val res = runCatching {
                app.get(base + path, headers = headers)
            }.getOrNull() ?: continue
            if (res.code !in 200..299) continue
            val body = res.text
            if (body.isBlank()) continue
            mbLastH5Host = if (base == mbWebBase) "web" else "h5api"
            return body to mbLastH5Host
        }
        return null
    }

    /** H5 POST through the pool (v7): same failover rule as
     * mbH5Get. Returns (body, hostLabel, code) — code is the
     * last HTTP code seen ("net" when no host answered). */
    private suspend fun mbH5Post(
        path: String,
        body: String,
        headers: Map<String, String>
    ): Triple<String?, String, String> {
        var lastCode = "net"
        for (base in mbH5Bases) {
            val res = runCatching {
                app.post(
                    base + path, headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json".toMediaType()
                    )
                )
            }.getOrNull() ?: continue
            lastCode = res.code.toString()
            if (res.code !in 200..299) continue
            val text = res.text
            if (text.isBlank()) continue
            mbLastH5Host = if (base == mbWebBase) "web" else "h5api"
            return Triple(text, mbLastH5Host, lastCode)
        }
        return Triple(null, "-", lastCode)
    }

    /** Absorb the guest JWT from a mobile response's x-user
     * header (v7 — Rennvol's absorbToken, behind
     * mbRennvolMobileAuth). Mobile responses only; H5 calls
     * never pass through here, so the web token cache is
     * never mixed into the mobile one. */
    private fun mbAbsorbToken(xUser: String?) {
        if (!mbRennvolMobileAuth) return
        val tok = xUser?.let {
            runCatching { JSONObject(it).optString("token") }.getOrNull()
        }?.ifBlank { null } ?: return
        mbCachedToken = tok
        mbCachedAt = System.currentTimeMillis()
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
     * v5 adds Phase C: the first MP4 id of each dub subject
     * (T52 — French MP4 id -> 1 French caption; other dub ids
     * -> 0; the Arabic-sub version's ids -> 0, T36), run after
     * Phase B; it can only add a missing side (trail "dh5=...",
     * "/dids=<dub ids>").
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
        dubCaps: List<Pair<String, String>> = emptyList(),
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val trail = ArrayList<String>()
        val triedIds = ArrayList<String>()
        val webTriedIds = ArrayList<String>()
        val dubTriedIds = ArrayList<String>()
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
                mbAbsorbToken(res.headers["x-user"] ?: res.headers["X-User"])
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
            // v7 — the failover host joins the loop: a base is
            // tried only when the previous one failed or came
            // back empty (the same rule as the pool).
            val h5Bases = listOf(mbWebBase, playBase, mbH5FailoverBase).distinct()
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
        // v7 — Rennvol ext-captions fallback (Stage A only):
        // the two get-ext-captions shapes v6's Stage A never
        // tried — resourceId=<stream id> with NO episode
        // parameter, and with episode=<real ep>. Fires ONLY
        // when Stage A left a language empty (ara or eng
        // null); stops the moment both are found. Same mobile
        // gateway host as the existing Stage A calls
        // (apig.inmoviebox.com) — no new host; the caption
        // files it returns come from MovieBox's own delivery
        // CDN (cacdn.hakunaymatata.com, the same CDN as the
        // H5 caption files — T62 + v5 t52 census).
        if (ara == null || eng == null) {
            for (sid in streamIds) {
                if (bothFound()) break
                tryMobile(
                    "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                        "get-ext-captions?subjectId=$subjectId" +
                        "&resourceId=$sid",
                    "rext"
                )
                if (bothFound()) break
                tryMobile(
                    "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                        "get-ext-captions?subjectId=$subjectId" +
                        "&resourceId=$sid&episode=$ep",
                    "rextEp"
                )
            }
        }
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
        // Phase C (v5): first MP4 id of each dub subject (T52:
        // French MP4 id -> 1 French caption; other dub ids -> 0;
        // the Arabic-sub version's ids -> 0, T36). Cheap (one
        // id per dub) and can only add a missing side.
        if (!bothFound() && dubCaps.isNotEmpty()) {
            h5Pass(dubCaps, "dh5", dubTriedIds)
        }
        if (ara == null && eng == null) {
            var out = "none:" + trail.joinToString("/")
            if (triedIds.isNotEmpty()) out += "/ids=" + triedIds.joinToString(",")
            if (webTriedIds.isNotEmpty()) out += "/wids=" + webTriedIds.joinToString(",")
            if (dubTriedIds.isNotEmpty()) out += "/dids=" + dubTriedIds.joinToString(",")
            return out
        }
        // v7 — dedupe by language code before the dual build:
        // if the same file was picked for both languages (one
        // entry labeled twice), keep it once (Arabic) instead
        // of merging a file with itself.
        if (ara != null && ara == eng) {
            eng = null
            trail += "dupcap=1"
        }
        return mbEmitUnion(ara, eng, subtitleCallback)
    }

    /** Dub subjects for a title (v5): H5 detail's
     * data.subject.dubs[] — (subjectId, detailPath, lanName),
     * the app's own "اختر الترجمة" picker verbatim (T45: PB 11
     * entries, T49: Other Mommy 6). Main subject first, then
     * the rest in server order. Detail failure -> empty (the
     * caller falls back to a single-entry list). */
    private suspend fun mbFetchDubs(
        detailPath: String,
        mainSubjectId: String
    ): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val json = runCatching {
            mbH5Get(
                "/wefeed-h5api-bff/detail?detailPath=" +
                    java.net.URLEncoder.encode(detailPath, "UTF-8"),
                mbJsonHeaders
            )?.first
        }.getOrNull() ?: return out
        val subj = runCatching {
            JSONObject(json).optJSONObject("data")?.optJSONObject("subject")
        }.getOrNull() ?: return out
        val dubs = subj.optJSONArray("dubs") ?: return out
        val seen = HashSet<String>()
        for (i in 0 until dubs.length()) {
            val o = dubs.optJSONObject(i) ?: continue
            val sid = o.optString("subjectId")
            val dp = o.optString("detailPath")
            val lan = o.optString("lanName").ifBlank { "Original Audio" }
            if (sid.isBlank() || dp.isBlank() || !seen.add(sid)) continue
            out += Triple(sid, dp, lan)
        }
        val main = out.firstOrNull { it.first == mainSubjectId }
        val rest = out.filter { it.first != mainSubjectId }
        return if (main != null) listOf(main) + rest else out
    }

    private data class MbPlayResult(
        val streams: List<JSONObject>,
        val base: String?,
        val code: String,
        val resourceId: String?
    )

    /** One subject's H5 play (v5): movie-box.co first, then
     * moviebox.sg — first base with streams wins (the v4
     * rule, unchanged). The Referer is the FULL player-page
     * URL for THIS subject's own detailPath, with its real
     * se/ep (the v17 shape proven on-device). */
    private suspend fun mbFetchPlayStreams(
        sid: String,
        dp: String,
        se: Int,
        ep: Int,
        token: String
    ): MbPlayResult {
        var playCode = "?"
        for (base in listOf("https://movie-box.co", "https://moviebox.sg")) {
            val seEp = if (se == 0 && ep == 0) {
                "detailSe=&detailEp="
            } else {
                "detailSe=$se&detailEp=$ep"
            }
            val referer = "$base/movies/$dp?id=$sid" +
                "&type=/movie/detail&$seEp&lang=en"
            val playUrl = "$base/wefeed-h5api-bff/subject/play" +
                "?subjectId=$sid&se=$se&ep=$ep" +
                "&detailPath=$dp" +
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
            val rid = d.optString("resourceId").ifBlank { null }
                ?: streamObjs[0].optString("resourceId").ifBlank { null }
            return MbPlayResult(streamObjs, base, playCode, rid)
        }
        return MbPlayResult(emptyList(), null, playCode, null)
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
        var dubCount = 0
        // v7 — emission accounting (DEBUG dup=/drop=): dup =
        // duplicate URLs removed, drop = fetched streams cut
        // by the 40-cap, nourl = streams with no URL at all.
        var dupCount = 0
        var noUrlCount = 0
        var capDropCount = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        if (subjectId.isBlank() || detailPath.isBlank()) {
            emitDebug(
                listOf(
                    "MovieBoxIN v7", "srv=0", "ext=0",
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
            // v5 — ALL sources (rennvol's per-dub iteration,
            // H5-ported): the dubs list from H5 detail, then
            // one H5 play per dub subject. Counted live (T46b):
            // 30 distinct streams for PB S1E1 (main 5 + Arabic
            // 5 + French 2 + Indonesian 3 + Kurdish 1 + Russian
            // sub 3 + Russian dub 4 + Spanish sub 1 + Spanish
            // dub 1 + esla 2 + ptbr 3) — more than the 14 the
            // other addons show, every one a real file.
            var dubs = mbFetchDubs(detailPath, subjectId)
            if (dubs.isEmpty()) {
                dubs = listOf(Triple(subjectId, detailPath, "Original Audio"))
            }
            dubCount = dubs.size
            val emittedUrls = HashSet<String>()
            val mainCaps = ArrayList<Pair<String, String>>()
            var mainStreamIds = emptyList<String>()
            var mainPlayBase: String? = null
            val dubCaps = ArrayList<Pair<String, String>>()
            for ((sid, dp, lan) in dubs) {
                if (elapsed() > 20000) break
                val play = mbFetchPlayStreams(sid, dp, se, ep, token)
                playCode = play.code
                if (play.base == null || play.streams.isEmpty()) continue
                if (resourceId == null) resourceId = play.resourceId
                streamsFound += play.streams.size
                // Caption candidates for this subject: first id
                // per format (the v4 shape). The main subject's
                // go to Phase A; each dub's first MP4 id goes to
                // Phase C.
                val capsHere = ArrayList<Pair<String, String>>()
                for (s in play.streams) {
                    val id = s.optString("id")
                    if (id.isBlank()) continue
                    val surl = mbStreamUrl(s) ?: continue
                    val fmt = when {
                        surl.contains(".mpd") -> "DASH"
                        surl.contains(".m3u8") -> "HLS"
                        else -> "MP4"
                    }
                    if (capsHere.none { it.first == id }) capsHere += id to fmt
                }
                if (sid == subjectId) {
                    mainCaps += capsHere
                    mainStreamIds = play.streams.mapNotNull { o ->
                        o.optString("id").ifBlank { null }
                    }.distinct()
                    mainPlayBase = play.base
                } else {
                    capsHere.firstOrNull { it.second == "MP4" }
                        ?.let { dubCaps += it }
                }
                for ((idx, s) in play.streams.withIndex()) {
                    if (emitted >= 40) {
                        capDropCount += play.streams.size - idx
                        break
                    }
                    if (elapsed() > 25000) break
                    val linkUrl = mbStreamUrl(s)
                    if (linkUrl == null) {
                        noUrlCount++
                        continue
                    }
                    if (!emittedUrls.add(linkUrl)) {
                        dupCount++
                        continue
                    }
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
                    // The dub name in the label, so the player
                    // shows which encode each source is.
                    val label = if (sid == subjectId) {
                        "MovieBox" + if (q != null) " ${q}p" else ""
                    } else {
                        "MovieBox $lan" + if (q != null) " ${q}p" else ""
                    }
                    emitMovieBoxLink(
                        linkUrl, label, signCookie, signHeaderKey,
                        play.base, dp, sid, se, ep, counting
                    )
                }
                if (emitted >= 40 || elapsed() > 25000) break
            }
            if (emitted > 0) {
                subDiag = mbEmitCaptions(
                    subjectId, detailPath, mainPlayBase ?: mbWebBase,
                    token, mainCaps, mainStreamIds, resourceId,
                    se, ep, t0, dubCaps, subtitleCallback
                )
            }
        }
        if (emitted == 0 || subDiag != "ar+en") {
            // A missing subtitle must never be silent again —
            // emit the DEBUG line even when playback links exist.
            // v6: the last search + TOP100 diagnostics ride
            // along (the home/search paths have no phone-visible
            // surface of their own).
            val diag = ArrayList<String>()
            diag += "MovieBoxIN v7"
            diag += "srv=$streamsFound"
            diag += "ext=$emitted"
            diag += "dubs=$dubCount"
            diag += "t=${elapsed() / 1000}s"
            diag += "tok=$tokVia"
            diag += "play=$playCode"
            diag += "dup=$dupCount"
            diag += "drop=$capDropCount"
            diag += "nourl=$noUrlCount"
            diag += "host=$mbLastH5Host"
            diag += "hdr=OK"
            diag += "se=$se"
            diag += "ep=$ep"
            diag += "sub=$subDiag"
            if (mbLastSearchDebug.isNotBlank()) diag += mbLastSearchDebug
            if (mbLastTopDebug.isNotBlank()) diag += mbLastTopDebug
            if (mbLastRankPg > 0) diag += "pg=${mbLastRankPg}"
            if (mbTop100Injected) diag += "top=INJ"
            emitDebug(diag, callback)
        }
        return true
    }
}
