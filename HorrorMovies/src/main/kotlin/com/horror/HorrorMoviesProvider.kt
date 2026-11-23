package com.horror

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HorrorMovies (أفلام رعب) — horror movies aggregated from the four
 * strongest horror lanes measured live 2026-10-25, MOVIES ONLY:
 *
 * - MOVIZLAND (moviz.land): horror lives under the TAG «رعب»
 *   (1,331 posts). Tag archives /tag/رعب/page/{n}/ render
 *   .archive-card x30 with data-title/url/image. Watch page
 *   {post}/watch/ lists .server-card embeds (extractors + digs +
 *   upbom direct mp4s). Post pages carry a real rating:
 *   <span class="rating">7.2/10</span> — shown on the detail page.
 * - EGYBEST (egy.bet — moved from egibest.life/vip 2026-11-15):
 *   /genre/رعب/ renders a.postBlockCol cards (title attr, img[data-src]
 *   poster). Title pages carry JSON-LD embedUrl + loadIframe(this,
 *   '<site>/watch/?url=<b64>') server wrappers + plain iframes;
 *   chain = dig-first for no-extractor hosts (v14) → extractors →
 *   second dig → ONE bounded relay attempt.
 * - MYCIMA (vid.mycima.cc): Melody horror category
 *   categories-cimawbas.php?cat=horror-movies (&page={n}),
 *   ul#pm-grid cards linking watch.php?vid={vid}; play.php
 *   lists the embeds (iframe srcs + /e/ + /embed/ URLs);
 *   chain = extractors → digs → relay.
 * - LAROSTV (larozatv.com): /category/رعب renders article.ay-card
 *   (movie-badged cards only). CATEGORY pages paginate with ?page=N
 *   (NOT /page/N — the /page/N path returns a stale 10-card set;
 *   ?page=2/?page=3 verified 20 NEW cards each, zero overlap).
 *   (movie-badged cards only); movie posts open the servers page
 *   /watch/{slug}/servers with [data-server-url] buttons (19 per
 *   title on the confirmed v2 chain: MixDrop-first + early stop).
 * - TOPCINEMA (topcinema.film → topcinemaa.vip): /genre/رعب/ renders
 *   a.recent--block cards. Watch page /watch/ lists li.server--item
 *   (data-id, data-server) → POST Single/Server.php → iframe.
 *   v9 FIX (the v8 bug — user report 2026-11-08 "توب سينما لا تعمل"):
 *   v8 built the POST URL from watchUrl minus "/watch/" — the FILM
 *   URL — so the POST went to <film-slug>/wp-content/.../Server.php
 *   and came back HTTP 301 + EMPTY body; all 10 server POSTs were
 *   silently skipped and only the single vidtube iframe survived
 *   (no stock extractor + CF-403). v9 POSTs to the watch page's
 *   ORIGIN (site root), verified live 2026-11-08: 10/10 servers
 *   return a real iframe. Resolvers: stock extractors StreamWish,
 *   Doodstream, Byse, Firestream, Streamtape; packed-JS digs
 *   OkStream + LuluStream (both yield direct tokenized m3u8s).
 *   VidTube has NO stock extractor and is CF-403 from the sandbox;
 *   UpDown + Filelions unproven — not needed, 7 paths is plenty.
 *   v10 (on-device v9: "Horror v9·Top | srv=7 | ext=0 | dig=0 |
 *   relay=0 | t=12s"): the v9 sequential POST loop ate the whole
 *   wall-clock budget on a slow phone — only 6 of 10 POSTs finished
 *   and the extractor loop never got a second, because runChain's
 *   caps shared loadLinks' t0. v10 POSTs in resolver-priority
 *   order (StreamWish, Doodstream, Streamtape, Byse, Firestream,
 *   OkStream, LuluStream, then the rest), stops at 6 embeds or
 *   8 s, each POST capped at 6 s; runChain now measures its caps
 *   from its own clock. DEBUG gains post=/pt=/hosts=.
 * - AFLAMSTREAM (aflamstream.com): /category/أفلام-رعب-… renders
 *   div.ht_grid_1_4 cards; post id → /watch/?id={id} →
 *   .server-btn[data-url] (luluvido + nuvorel direct m3u8s verified
 *   live 2026-11-07, 3/3 films). v9 re-check (2026-11-08, user
 *   report "أفلام ستريم لا تعمل"): 3 films (Apocalypse Z 14219,
 *   Crawlers 14314, Beware Boiuna 14310) all carry the SAME 4
 *   servers (nuvorel, luluvido, playmogo, streamtape); nuvorel
 *   master.m3u8 → HTTP 200 (2,242 B) and luluvido unpacked
 *   master.m3u8 → HTTP 200 — the chain is ALIVE from the sandbox
 *   and NOT reproducible as broken here. If v9 still fails
 *   on-device, the DEBUG line (srv=/ext=/dig=/relay=) pinpoints it:
 *   srv=0 means the watch fetch failed; srv=4 ext=0 dig=0 means
 *   the embeds all failed on the phone (extractor/CF per-host).
 * - CIMA4Y (cima4y.net): HELD OUT of v8 (2026-11-07 12:06).
 *   The horror source itself is real — /category/افلام-اجنبي?genre=رعب
 *   serves 50 FILM cards/page (all /film/, 0 episodes), &page=N
 *   paginates with zero overlap (p1-p3 verified, 150 unique films)
 *   — but the watch-page playback chain is NOT cracked for a
 *   static fetcher: Cima4y v1 AND v2 both failed on-device with
 *   emb=0 (v2: page=200, len=55133, real page, NOT a CF block —
 *   cf=1 was a false alarm from the CF jsd script). The embed URLs
 *   genuinely are NOT in the static HTML; they resolve by JS/AJAX
 *   after a server click. Shipping a lane that regex-extracts the
 *   static watch page would repeat the exact failure twice proven
 *   on the phone. The lane goes in ONLY when Cima4y v3's
 *   exfiltration DEBUG (iframe srcs, servers-section snippet,
 *   in-session /m/ fetch) proves a working mechanism. Do NOT add
 *   it from sandbox fetches alone — the sandbox cannot even fetch
 *   the watch page (connection reset, CF Turnstile).
 * - MYCIMA (vid.mycima.cc): DEAD from non-browser clients —
 *   HTTP 403 Cloudflare "Just a moment..." challenge on the
 *   category AND the homepage (verified 2026-11-07). Lane kept
 *   (a real phone may clear the challenge) but yields 0 cards
 *   from the sandbox.
 *
 * PAGINATION (v8, verified live 2026-11-07): hasNext is the
 * next-page link AND page < last page parsed from the pagination
 * bar — the old link-only check lied on the last page (TopCinema
 * p74 still linked /page/75/, which is an empty page). Per-lane
 * totals: moviz 45p (30/p, 17 on p45), eg 6p (36/p, 12 on p6),
 * lar 5p (20/p, 3 on p5), top 74p (54/p, 32 on p74), af 5p
 * (20/p, 14 on p5), cima4y 50/p (hasNext = cards >= 50).
 * MOVIES-ONLY (v8): moviz/eg/af lanes are now filtered with
 * isMovieTitle — the moviz tag is mostly series episodes
 * (p1: 11 films of 30; p2-p5: 0 films; p10+: films again), so
 * unfiltered cards showed episode posts as "films".
 *
 * CENSUS (v9, live 2026-11-08, REAL direct links only):
 * moviz 3/3 alive (vidspeed packed-unpack m3u8; +uqload once),
 * eg 3/3 alive (1vid packed-unpack m3u8), af 3/3 alive
 * (nuvorel direct m3u8 → 200; luluvido packed-unpack m3u8 → 200;
 * +playmogo/doodstream +streamtape via stock extractors),
 * lar 0/3 direct-in-sandbox (13-19 embeds: mixdrop/doodstream/
 * share4max — MixDrop URL lives in MDCore.wurl, only the
 * CloudStream extractor resolves it; doodstream 403s from the
 * sandbox), top 10/10 Server.php POSTs return a real iframe at
 * the site ROOT (v8's slug-path POST 301s empty — THE v8 BUG);
 * okstream + luluvdo packed-unpack m3u8s verified; myc 0 cards
 * (403 CF), cima4y watch pages connection-reset from the sandbox
 * (CF Turnstile).
 *
 * SPEED (his standing order): every loadLinks runs on a wall-clock
 * budget — extractors fastest-first, capped links, digs capped,
 * relay only when nothing playable exists yet.
 *
 * v14 (2026-11-14, Dead Howling 2026 on-device: "DEBUG Horror
 * v10·EgyBest | srv=17 | ext=0 | dig=0 | relay=0 | t=117s"):
 * the 17 servers are real (JSON-LD + 17 loadIframe wrappers) but
 * 6 are mixdrop.ps COPIES of a DELETED file ("WE ARE SORRY — We
 * can't find the video"), and 9 more have NO stock extractor at
 * all (vidmoly.org, uqload.vc, mixdrop.top, 1vid, vidspeed,
 * byse mirrors, cybervynx — the registry only covers vidmoly.net,
 * uqload.com, mixdrop.co/.ps/.to, byse.sx, ...). The old chain ran
 * extractors first, so the dead mixdrops ate the 11 s extractor
 * budget and the ~5 s of dig budget left died on the same dead
 * hosts before vidmoly / 1vid / vidspeed / uqload / mixdrop.top
 * were ever tried — all five yield a real direct link when dug
 * (verified live on Dead Howling, Insidious Possession and Marrow
 * 2026). Then the relay phase ran unbounded app.get calls (2
 * attempts each) against the sleeping Render service and burned
 * ~100 s for zero links. v14: dig-first for no-extractor hosts,
 * extractors capped at 2 per host, second dig pass, ONE bounded
 * (8 s) relay attempt; extractDirect also reads MDCore.wurl and
 * protocol-relative //...mp4|m3u8 URLs (the live mixdrop.top
 * link). DEBUG marker is now "Horror v14·<lane>".
 *
 * v15 (2026-11-15, EgyBest section review — user report: films
 * don't play, film info doesn't show, trailer doesn't show):
 * ONE root cause for all three, proven live: the site moved
 * domains AGAIN (egibest.life + egibest.vip both 301 to egy.bet;
 * card hrefs are now https://egy.bet/... with NO "egibest"
 * substring anywhere). v14's contains("egibest") checks in
 * load() and loadLinks() therefore matched NOTHING: every EgyBest
 * card fell to movieFallback (title "فيلم", no poster, no plot,
 * trailerFor("فيلم") -> no Latin -> no trailer) and loadLinks
 * fell to the else branch -> "DEBUG Horror | unknown-lane" +
 * Big Buck Bunny test clip (exactly the user's 02:06 shot).
 * The detail pages themselves are healthy (og:title / og:image /
 * og:description all present on 6/6 films) and trailerFor
 * matches the correct official trailer on 6/6 Latin titles live,
 * and the v14 dig chain still yields direct links on today's
 * egy.bet embeds (1vid/vidmoly/vidspeed 200, uqload + mixdrop.top
 * links found). Fix: isEgyBest() host matcher used in load(),
 * loadLinks() and the EgyBest embed/iframe filters; section +
 * search base URLs moved to egy.bet; movieFallback trims the
 * trailing slash before taking the slug title; poster URLs get
 * non-ASCII percent-encoding (site posters carry raw Arabic in
 * the filename). Trailer v3 rule untouched: Arabic-only titles
 * still get poster only, no YouTube query. DEBUG marker is now
 * "Horror v15·<lane>".
 *
 * v16 (2026-11-15, NEW lane "أفلام رعب — MovieBox", FIRST
 * section by the user's explicit order, Arabic-subtitled ONLY):
 * clean-room reimplementation of the MovieBoxProvider v54 (CNC)
 * flow, recovered from its dex — NO code copied, no .cs3
 * redistributed. Catalog = the web ranking-list
 * moviebox.sg/wefeed-h5api-bff/ranking-list/content?id=
 * 8614980535946986176 (verified live p1-6: 17/15/18/18/18/18
 * films; the Arabic filter accepts 2/14/17/17/18/15 = 83 in 6
 * pages). Filter: subtitles field holds comma-separated language
 * names, Arabic as "اَلْعَرَبِيَّةُ" (fully vocalized); normalize
 * (strip diacritics/tatweel, أ/إ/آ→ا, ة→ه, ى→ي) then accept if
 * any name contains "عرب" — verified live on 5 films (Other
 * Mommy, Conjuring: Last Rites, Five Nights at Freddy's, 28
 * Years Later: The Bone Temple accepted; Beware Boiúna English-
 * only excluded). Detail = wefeed-h5api-bff/detail?detailPath=
 * (fields live under data.subject). Playback = guest JWT token
 * (mobile gateway first, web host fallback — the web host grants
 * a real JWT in its x-user header, verified live; the token is
 * accepted by the backend: search 400 "invalid token" -> 200
 * with it) + X-Client-Token + x-tr-signature (HmacMD5 over the
 * canonical string, keys double-base64 from the dex) -> GET
 * {movie-box.co|moviebox.sg}/wefeed-h5api-bff/subject/play ->
 * data.streams[]/dash[]/hls[] (signCookie per stream). Captions
 * via get-ext-captions (Arabic pick) — fail-soft. KNOWN LIMIT
 * (stated plainly): the sandbox is region-blocked (mobile
 * gateway 403 "Service not available in current region"; the
 * play endpoint returns empty streams even with a valid token),
 * so playback is NOT verifiable from here — the original
 * MovieBoxProvider v54 WORKS on the user's phone (his word
 * 2026-11-15 21:44), so the same flow is expected to work
 * on-device; the on-device verdict is his. Apocalypse Z is NOT
 * in the MovieBox ranking p1-6 nor in its H5 search (0 results)
 * at v16 build time — use a listed Arabic-subtitled title
 * (Conjuring: Last Rites, 28 Years Later: The Bone Temple,
 * Other Mommy) for the on-device test. DEBUG marker is now
 * "Horror v16·MovieBox".
 *
 * v17 (2026-11-15, MovieBox rebuilt on the IN flow + pagination
 * fix + franchise parts): the user's on-device verdict on v16
 * was "فيلمين فقط ولا يستغل" (only two films, playback does not
 * work) — page 1 carries only 2 Arabic films and v16's
 * hasNext = rawCount >= 20 stopped the section there; and he
 * ordered "استبداله بهذه" — replace the implementation with
 * MovieBoxIN (CNC v56), which works on his phone. Dex comparison
 * (IN v56 vs v54) proved the playback chain is IDENTICAL between
 * the two (same token fetch, same signing keys, same play
 * endpoint, same emit logic — byte-identical emit code); the
 * differences are only in getMainPage (IN fetches its home tabs
 * dynamically via wefeed-mobile-bff/tab-operating, v54 hardcodes
 * 13 category tabs) and dub handling. So v16's failure is in OUR
 * reimplementation's divergences from the CNC flow, fixed here:
 * (1) the play-call Referer was a relative path
 * "/movies/?id=..." — CNC sends the FULL player-page URL
 * "<playBase>/movies/<detailPath>?id=<subjectId>&type=/movie/
 * detail&detailSe=&detailEp=&lang=en" (proven from the IN dex,
 * loadLinks$1 lines ~110f-1133; v54 identical); (2) detailPath
 * was URL-encoded in the play URL — CNC appends it raw;
 * (3) emitted links carried Referer = the same relative path,
 * no Origin, and the signed cookie under hardcoded "Cookie" —
 * CNC's emit (loadLinks$emitStream$2, lines 780-794) sends for
 * VIDEO links Origin = subDomain, Referer =
 * "<subDomain>/movies/<subPath>?id=...&type=...", the mobile
 * Pixel 9 Chrome 154 UA, and the cookie under the stream's own
 * signHeaderKey (default "X-MB-Token"); non-VIDEO links get
 * {Referer: "https://movie-box.co/", desktop Chrome 154 UA}.
 * Here subDomain = the play base that returned the streams and
 * subPath = detailPath, so the emitted Referer is exactly the
 * player-page URL the backend validated when issuing the
 * streams (judgment call — the dex does not name how CNC
 * derives them; the alternative, stream-URL origin, is noted
 * in v17/NOTES.md). One CNC detail NOT reproduced: a header
 * whose value is {"timezone":"Asia/Calcutta"} (IN dex 113d) —
 * its header NAME could not be recovered from the dex (no
 * timezone header string exists in it) and guessing a name is
 * worse than omitting it. Pagination: hasNext now reads
 * data.pager.hasMore AND stops only when a page brings zero NEW
 * raw items (seen-set by detailPath, cleared on page 1) — a page
 * with zero Arabic cards does NOT end the section. Verified
 * live: 8 pages, 128 raw, 106 Arabic, 106 unique, 0 dupes,
 * hasMore=false on p8. NEW: franchise parts on the film page
 * (his order 2026-11-15 22:18) — load() searches MovieBox for
 * the film's base name and attaches every matching part, sorted
 * by year then part number, Arabic-subtitled only, fail-soft.
 * Verified live: Saw -> 8 parts (2004..2023, X last); The
 * Conjuring -> 2 parts (the 2013/2016 films are not in the
 * MovieBox search index at all); 28 Years Later -> 2 parts.
 * DEBUG marker is now "Horror v17·MovieBox".
 *
 * v18 (2026-11-15, MovieBox Arabic captions): the user's
 * on-device verdict on v17 was "جيد يشتغل moivebox لكن
 * الترجمة غير موجودة" — playback works, subtitles missing.
 * TWO defects, both proven live from the sandbox before any
 * code changed:
 * (1) ROUTE: v17 fetched captions ONLY from the mobile
 * gateway (apig.inmoviebox.com get-ext-captions /
 * get-stream-captions) — 403 "Service not available in
 * current region" from every US IP tested, and the web BFF
 * caption endpoint 403s "invalid region" the same way, while
 * playback (H5 bases) works — so on the user's US network
 * the caption call died silently (fail-soft, no diagnostic).
 * The H5-api-bff caption endpoint
 * (moviebox.sg/wefeed-h5api-bff/subject/caption) answers
 * HTTP 200 from a US IP — NOT region-gated — so it goes
 * FIRST in v18, with the REAL stream id + format from the
 * play response (id=subjectId alone returns an empty
 * captions array; a missing id is a 400 "empty id"). The
 * mobile routes stay as fallback. Detail/play responses
 * carry NO captions (only the subtitles language-name list
 * and an empty resource object).
 * (2) FIELDS: v17's pick checked `language` == "ar"/"ara"
 * and lanName for "عرب" — but the caption object fields
 * (proven from the CNC IN dex + the CloudX model) are lan
 * (code, e.g. "ar"), lanName (display name, e.g. "Arabic"),
 * url — there is NO "language" field, and lanName is Latin,
 * so neither check could ever match. v18 accepts lan /
 * language == ar|ara, lanName containing "عرب" (normalised)
 * or Latin "arab". The CNC dex emits SubtitleFile with
 * (lang, url) and NO headers, so no headers are passed.
 * NEW: the DEBUG line now carries "sub=ar" or
 * "sub=none:<route>=<code>/..." (e.g. "none:h5=200-empty/
 * mob=403") and is emitted even when playback links exist —
 * a missing subtitle is never silent again. Live caption
 * tests on the 4 films: see v18/NOTES.md.
 * DEBUG marker is now "Horror v18·MovieBox".
 *
 * v19 (2026-11-15, MovieBox dual subtitles «العربية +
 * الإنجليزية»): the user's on-device verdict on v18 was
 * "ظهرات الترجمة في قسم moviesbox أفلام رعب" (Arabic
 * subtitles now show) + his order "اضيف عربي + إنجليزي في
 * نفس الوقت" — add Arabic + English at the same time. The
 * same captions array that returns Arabic returns English
 * too, so mbPickCaptions takes the first of each; when both
 * exist the menu gets TWO entries — «العربية» (raw URL, as
 * in v18) and «العربية + الإنجليزية» (Goojara v20 pattern:
 * the anime-subs relay's /relay/mergesubs?eng=&ara=, which
 * fetches both files and returns one merged VTT — verified
 * live from the sandbox on stored test files: 200, merged
 * VTT carries both languages' cues). The mergesubs URL is
 * verified with one GET before advertising it; if it does
 * not serve, the fallback merges locally on the phone's
 * network (which reaches MovieBox) — tolerant VTT/SRT cue
 * parser + overlap merge (Arabic line first, English
 * second) + /relay/store hosting. One side only -> that
 * side alone ("ar" / "en"); both sides but no dual file
 * -> "ar+mf" (Arabic single still emitted). DEBUG: "ar+en"
 * is now the good outcome — the line is emitted whenever
 * the outcome is anything else. The trailer logic in this
 * file is UNTOUCHED in v19 (a separate trailer fix takes
 * HorrorMovies as v20 next). HONEST LIMIT: no real MovieBox
 * caption file was ever retrieved from the sandbox (the
 * play endpoint returns empty streams from a datacenter IP,
 * so no real stream id exists here) — whether the relay can
 * fetch MovieBox's actual caption URLs is unproven until
 * his phone tries; the local-merge fallback exists exactly
 * for that case. See v19/NOTES.md.
 * DEBUG marker is now "Horror v19·MovieBox".
 *
 * v22 (2026-11-18, TEST — MovieBox home lane temporarily
 * DISABLED to isolate the re-add crash): Mohamed reported
 * 2026-11-18 that CloudStream exits when HorrorMovies is
 * re-added (delete = app works, re-add v21 = crash; offline
 * test did NOT open it) and suspects the MovieBox section.
 * Change is ONLY the home lane: the MovieBox entry is removed
 * from mainPageOf and getMainPage returns empty for lane
 * "moviebox" if ever called. ALL MovieBox playback code is
 * untouched (load() MovieBox branch, loadLinksMovieBox,
 * mbWebToken/mbGuestToken, franchise parts, tokens,
 * subtitles) — a film already opened via MovieBox still
 * plays. This is a diagnostic test build, not a redesign;
 * the lane returns only when the phone verdict proves it
 * was (or was not) the crash cause.
 *
 * v23 (2026-11-18, RESTORE — MovieBox home lane restored
 * per Mohamed's explicit order «إعادة القسم»): his on-device
 * verdict 2026-11-18 19:38 EET was v22 STILL EXITS, so the
 * lane was NOT the crash cause. The MovieBox entry is back
 * FIRST in mainPageOf (his standing 2026-11-15 order) and
 * the getMainPage moviebox branch is restored to the v21
 * form. This version does NOT claim to fix the exit crash;
 * the crash cause is still undecided and investigation
 * continues after the restore.
 *
 * v24 (2026-11-19, FAIL-FAST — per-lane 10s load timeout,
 * NO deletions): Mohamed's diagnosis 2026-11-19 00:58 EET
 * is slow loading — the home waits for loading and the page
 * never loads («بطء التحميل هو السبب يعني ينتظر تحميل ولا
 * تتحمل الصفحة») — and he ordered «لا تحدف شي» (delete
 * nothing). getMainPage used unbounded app.get for every
 * lane, so one slow/hung site could hang the whole home and
 * the app exits. Every home fetch now goes through the
 * HttpURLConnection helper with a 10 s connect+read timeout
 * (same pattern as the existing httpGetText): a slow lane
 * returns empty quickly instead of hanging home. ALL 7 lanes
 * stay, MovieBox FIRST, no lane/extension/code deleted.
 *
 * v25 (2026-11-20, DIAGNOSTIC - empty home): v24 still
 * exits on the phone (older CloudStream, only our repo), so
 * the crash was NOT slow loading, NOT the MovieBox lane,
 * NOT the app version, NOT a repo mix. getMainPage returned
 * EMPTY (no sections, no fetches). Verdict 2026-11-22:
 * Mohamed deleted EVERYTHING, added HorrorMovies alone
 * (v25, current published) - the app is STABLE, does not
 * exit. So the addon itself (dex/init) is NOT the crash
 * cause; the crash is in a lane's data/fetch, or in
 * interaction with other addons, or in stored data.
 *
 * v26 (2026-11-22, RESTORE - all 7 lanes back, v24 home
 * code byte-identical): v25 proved the empty home opens.
 * This build restores the full v24 getMainPage (MovieBox
 * FIRST, per-lane 10 s fail-fast, all 7 lanes) to test the
 * next branch: if v26 opens alone, the lanes are exonerated
 * and the crash was other addons / stored data; if v26
 * exits alone, a lane's data is the cause and lanes are
 * bisected one at a time next. No other change vs v25.
 *
 * v27 (2026-11-22, BISECT 1/2 - first 3 lanes only):
 * v26 (all 7 lanes) EXITS on the phone on a clean slate
 * (only HorrorMovies, fresh data), while v25 (empty home)
 * was STABLE - so the crash is in a lane's data/fetch.
 * This build declares ONLY moviebox + moviz + eg in
 * mainPageOf (the other 4 lanes are not fetched at all);
 * getMainPage code is byte-identical to v24/v26. If v27
 * EXITS, the culprit is in these 3 lanes; if v27 is
 * STABLE, it is in myc / lar / top / af. Next build
 * splits the guilty half again.
 *
 * v28 (2026-11-22, MOVIEBOX-ONLY - direct test of his
 * claim): Mohamed 2026-11-22 19:07: "لا هذه المشكلة
 * ظهرت بعد قسم moivebox" - the crash appeared AFTER the
 * MovieBox section was added (v17 introduced the lane).
 * This build declares ONLY moviebox in mainPageOf; v27
 * (moviebox+moviz+eg) is superseded untested. If v28
 * EXITS alone, the MovieBox lane is the cause (then the
 * bad card/data inside it is hunted next: perPage cap
 * or first-half/second-half of its 20 cards). If v28 is
 * STABLE alone, the crash needs MovieBox + another lane,
 * or the timeline points to a data change, and v27's set
 * is tested next. getMainPage byte-identical to v24/v26.
 *
 * v29 (2026-11-22, MOVIZ-ONLY - is it MovieBox-specific
 * or any section?): v28 (MovieBox alone) EXITS, but the
 * live MovieBox list is CLEAN - 17 films, all normal
 * titles/posters/detailPaths, 17/17 Arabic after the
 * diacritic-strip normalizer (the raw "0 Arabic" count
 * was my error: subtitles carry tashkeel). The one 404
 * poster (Obsession) was transient CDN propagation -
 * it returns 200 now. So no bad card is visible in the
 * sandbox region's data. This build declares ONLY the
 * moviz HTML lane: if v29 EXITS too, the crash is in
 * home sections generally (not MovieBox data); if v29
 * is STABLE, it is MovieBox-specific (fetch/region/
 * timing on his line). getMainPage byte-identical.
 *
 * v30 (2026-11-23, ROLLBACK to the pre-problem home -
 * his order "ارجع الإضافة الي الإصدار قبل المشكلة"):
 * the MovieBox home lane was added in v16 (2026-11-15),
 * so the version before the problem is v15's home: the
 * 6 HTML lanes (moviz, eg, myc, lar, top, af), NO
 * MovieBox lane in mainPageOf. v29 (moviz-only) is
 * superseded UNTESTED. Everything else is kept: parts
 * row v21, trailer v4, subtitle gear, fail-fast v24.
 * The MovieBox branch code in getMainPage/load/loadLinks
 * stays but is unreachable while no lane declares it,
 * so MovieBox can be restored if its cause is found.
 * getMainPage byte-identical to v24/v26.
 *
 * v32 (2026-11-23, his order «لقسم idmb — نفس باقي
 * الاقسام»): TWO things for the IMDb section, the same
 * as the other sections. (1) SUBTITLE GEAR, three modes
 * (Goojara/AnimeVerse pattern): «العربية» / «الإنجليزية» /
 * «العربية + الإنجليزية» (default dual), chosen in the
 * settings gear — the old single English on/off switch is
 * replaced (migration: old ON -> dual, old OFF -> ar).
 * OpenSubtitles (key-free, IMDb-id resolved) supplies the
 * Arabic + English files with the same sync-first ranking
 * as before (23.976fps BluRay/WEB UTF-8); dual merges them
 * through the relay mergesubs (verified 200+WEBVTT first)
 * with the local merge as fallback, exactly like the
 * MovieBox dual. MovieBox captions follow the same mode
 * (mbEmitFromArray reads HorrorSubs.mode). The gear is
 * addon-wide, so every section's films get it, IMDb
 * included. (2) FASSELHD as an IMDb-section source:
 * imdbResolveTitle now tries MovieBox, then FasselHD
 * (panel API kahitdgku.com/faselhd15/public/api, search
 * endpoint, exact normalized-title match, movie rows
 * only), then the 6 HTML lanes — first hit wins. A
 * FasselHD-resolved film plays through the new load() +
 * loadLinksFassel branches (detail -> videos -> stock
 * extractors, direct .mp4/.m3u8 emitted as-is; the
 * probe/dig/relay phases of the FasselHD addon are NOT
 * ported — a server no stock extractor handles yields
 * nothing). Verified live 2026-11-23: search «Weapons»
 * returns the 2025 film first (id 29099, the title
 * MovieBox lacks), detail carries 28 video servers.
 *
 * v31 (2026-11-23, NEW "IMDb" section - his order):
 * the section takes its film list from IMDb's horror
 * interest page (imdb.com/interest/horror/) and brings
 * each film from MovieBox and the other sections, ONE
 * card per film, no duplicates. IMDb answers plain HTTP
 * with an AWS WAF challenge (HTTP 202), so the provider
 * cannot fetch the page live - the list is a SEED
 * extracted 2026-11-23 via the real browser (29 movies;
 * the page's series are excluded, this addon is movies-
 * only). At runtime each seed title is resolved LIVE:
 * MovieBox H5 search first (exact normalized-title
 * match, latest year wins among remakes), then the 6
 * HTML lanes' search pages in order, first hit wins;
 * the card shows the IMDb title + rating ("Title ★ 7.4").
 * 10 seed titles per page. MovieBox HOME lane stays OUT
 * (v30 rollback). Risk: v30's verdict is still pending,
 * and this section makes MovieBox search calls from the
 * home - if v31 exits, this section is the first suspect.
 *
 * RATING: CloudStream's card type has no rating slot in this app
 * version, so ratings ride the detail page: the plot line opens
 * with «★ التقييم: X/10» wherever the source page provides one
 * (MovizLand verified; other lanes as their pages provide).
 */
class HorrorMoviesProvider : MainAPI() {
    override var mainUrl = "https://www.moviz.land"
    override var name = "أفلام رعب"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
    )
    private val relayBase = "https://egydead-stremio.onrender.com"

    override val mainPage = mainPageOf(
        // v30 ROLLBACK: the v15 home (before the MovieBox
        // lane, added in v16) - 6 HTML lanes, no MovieBox.
        // v31: IMDb section FIRST (seed list, resolved live).
        "imdb:imdb" to
            "IMDb",
        "moviz:https://www.moviz.land/tag/%d8%b1%d8%b9%d8%a8/" to
            "أفلام رعب — موفيز لاند",
        "eg:https://egy.bet/genre/%d8%b1%d8%b9%d8%a8/" to
            "أفلام رعب — إيجي بست",
        "myc:https://vid.mycima.cc/categories-cimawbas.php?cat=horror-movies" to
            "أفلام رعب — ماي سيما",
        "lar:https://larozatv.com/category/%D8%B1%D8%B9%D8%A8" to
            "أفلام رعب — لاروز",
        "top:https://topcinema.film/genre/%D8%B1%D8%B9%D8%A8/" to
            "أفلام رعب — توب سينما",
        "af:https://aflamstream.com/category/%D8%A3%D9%81%D9%84%D8%A7%D9%85-%D8%B1%D8%B9%D8%A8-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9-%D9%88%D8%AC%D8%AF%D9%8A%D8%AF%D8%A9/" to
            "أفلام رعب — أفلام ستريم"
    )

    private fun cleanHtml(s: String): String =
        runCatching { Jsoup.parse(s).text() }.getOrDefault(s).trim()

    private fun cleanMovieTitle(raw: String): String {
        var t = cleanHtml(raw).removePrefix("مشاهدة").trim()
            .removePrefix("فيلم").trim()
        t = t.replace(Regex("""\s*[-|]\s*(MovizLand|موفيز لاند|Egybest|ايجى بست|MyCima|ماي سيما|LaRosTV|لاروز|توب سينما|TopCinema|سلسلة افلام الرعب|أفلام ستريم).*${'$'}""", RegexOption.IGNORE_CASE), "").trim()
        return t
    }

    private fun movieCard(title: String, url: String, poster: String?): SearchResponse? {
        val name = cleanMovieTitle(title)
        if (name.isBlank() || url.isBlank()) return null
        return newMovieSearchResponse(name, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun isMovieTitle(title: String): Boolean {
        val t = cleanHtml(title)
        return !t.contains("مسلسل") && !Regex("""الحلقة\s*\d+""").containsMatchIn(t)
    }

    // ---------------------------------------------------------------
    // Home
    // ---------------------------------------------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (lane, base) = request.data.split(":", limit = 2)
        // v31: IMDb section — seed titles from IMDb's horror
        // interest list, each resolved LIVE to one card
        // (MovieBox search first, then the 6 HTML lanes'
        // search, first hit wins) and deduped by normalized
        // title. 10 seed titles per page.
        if (lane == "imdb") {
            val pageSize = 10
            val start = (page - 1) * pageSize
            if (start < 0 || start >= imdbHorrorSeed.size) {
                return newHomePageResponse(emptyList(), false)
            }
            val slice = imdbHorrorSeed.subList(
                start, minOf(start + pageSize, imdbHorrorSeed.size)
            )
            val cards = ArrayList<SearchResponse>()
            val seenTitles = HashSet<String>()
            for ((title, rating) in slice) {
                if (!seenTitles.add(imdbKey(title))) continue
                val (url, poster) = runCatching {
                    imdbResolveTitle(title)
                }.getOrNull() ?: continue
                val display = if (rating.isNotBlank()) {
                    "$title ★ $rating"
                } else {
                    title
                }
                movieCard(display, url, poster)?.let { cards += it }
            }
            return newHomePageResponse(
                listOf(
                    HomePageList(request.name, cards, isHorizontalImages = true)
                ),
                start + pageSize < imdbHorrorSeed.size
            )
        }
        // v17: MovieBox — JSON ranking-list, NOT an HTML page.
        // Arabic-subtitled ONLY: a film with no Arabic in its
        // subtitles field never becomes a card. Pagination (fixed
        // v17 — v16 used rawCount >= 20, but pages return 17/15/18
        // films, so page 1 gave hasNext=false and the section
        // stopped at 2 Arabic films): hasNext reads
        // data.pager.hasMore AND the section stops only when a
        // page brings zero NEW raw items (seen-set by detailPath,
        // cleared on page 1) — a page with zero Arabic cards does
        // NOT end the section, and a clamped past-the-end page
        // (zero new items) ends it even if hasMore claims true.
        if (lane == "moviebox") {
            val url = if (page <= 1) base else "$base&page=$page"
            // v24: fail-fast 10 s fetch — a slow MovieBox ranking
            // call returns empty instead of hanging the home.
            val text = httpGetTextHeaders(url, homeFetchTimeoutMs, mbJsonHeaders)
            if (text.isNullOrBlank()) {
                return newHomePageResponse(emptyList(), false)
            }
            val dataObj = runCatching {
                JSONObject(text).optJSONObject("data")
            }.getOrNull()
            val list = dataObj?.optJSONArray("subjectList")
            if (list == null) {
                return newHomePageResponse(emptyList(), false)
            }
            if (page <= 1) mbSeenDetailPaths.clear()
            val pager = dataObj.optJSONObject("pager")
            val rawCount = list.length()
            var rawNew = 0
            val cards = ArrayList<SearchResponse>()
            for (i in 0 until rawCount) {
                val o = list.optJSONObject(i) ?: continue
                val dp = o.optString("detailPath")
                if (dp.isBlank()) continue
                if (!mbSeenDetailPaths.add(dp)) continue
                rawNew++
                if (!hasArabicSub(o.optString("subtitles"))) continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                val poster = o.optJSONObject("cover")
                    ?.optString("url")?.ifBlank { null }
                movieCard(
                    title,
                    "$mbWebBase/ar/moviedetail/$dp",
                    poster
                )?.let { cards += it }
            }
            val apiHasMore = if (pager != null && pager.has("hasMore")) {
                pager.optBoolean("hasMore")
            } else {
                rawNew > 0
            }
            return newHomePageResponse(
                listOf(
                    HomePageList(request.name, cards, isHorizontalImages = true)
                ),
                apiHasMore && rawNew > 0
            )
        }
        val url = when (lane) {
            "moviz" -> if (page <= 1) base else base + "page/$page/"
            "eg" -> if (page <= 1) base else base + "page/$page/"
            "top" -> if (page <= 1) base else base + "page/$page/"
            "af" -> if (page <= 1) base else base + "page/$page/"
            "myc" -> if (page <= 1) base else base + "&page=$page"
            "lar" -> if (page <= 1) base else base + "?page=$page"
            else -> base
        }
        // v24: fail-fast 10 s fetch — a slow site returns empty
        // instead of hanging the home. Jsoup.parse(html, url)
        // keeps the base URI so abs:href / abs:src still resolve.
        val html = httpGetTextHeaders(url, homeFetchTimeoutMs, browserHeaders)
            ?: return newHomePageResponse(emptyList(), false)
        val doc = runCatching {
            Jsoup.parse(html, url)
        }.getOrNull() ?: return newHomePageResponse(emptyList(), false)
        val cards = when (lane) {
            "moviz", "eg", "myc", "lar", "top", "af" -> parseLaneCards(lane, doc)
            else -> emptyList()
        }
        // v8: a next-page link alone lies on the last page (TopCinema
        // p74 still links /page/75/, which is empty) — hasNext also
        // requires page < last page parsed from the pagination bar.
        val nextLink = when (lane) {
            "moviz", "eg", "top", "af" -> doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
            "myc" -> doc.select("a[href*=page=${page + 1}]").isNotEmpty()
            "lar" -> doc.select("a[href]").any {
                it.attr("href").contains("?page=${page + 1}")
            }
            else -> false
        }
        val lastPage: Int? = when (lane) {
            "moviz", "eg", "top", "af" -> doc.select("a[href*=/page/]")
                .mapNotNull { a ->
                    // Numbered links only: the » next-arrow also
                    // carries a /page/N+1/ href and would fake a
                    // further last page (TopCinema p74 links » to
                    // an empty p75 while the numbers stop at 74).
                    if (a.text().trim().toIntOrNull() == null) {
                        return@mapNotNull null
                    }
                    Regex("""/page/(\d+)/""").find(a.attr("href"))
                        ?.groupValues?.get(1)?.toIntOrNull()
                }.maxOrNull()
            "myc", "lar" -> doc.select("a[href]")
                .mapNotNull { a ->
                    if (a.text().trim().toIntOrNull() == null) {
                        return@mapNotNull null
                    }
                    Regex("""[?&]page=(\d+)""").find(a.attr("href"))
                        ?.groupValues?.get(1)?.toIntOrNull()
                }.maxOrNull()
            else -> null
        }
        val hasNext = nextLink && (lastPage == null || page < lastPage)
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
            hasNext
        )
    }

    private fun parseLaneCards(lane: String, doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        when (lane) {
            "moviz" -> for (el in doc.select(".archive-card")) {
                // v8 movies-only: the tag mixes series episodes in
                // (p1: 11 films of 30; p2-p5: 0 films).
                val rawTitle = el.attr("data-title")
                if (!isMovieTitle(rawTitle)) continue
                movieCard(
                    rawTitle,
                    el.attr("data-url"),
                    el.attr("data-image").ifBlank { null }
                )?.let { out += it }
            }
            "eg" -> for (a in doc.select("a.postBlockCol")) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val img = a.selectFirst("img")
                val poster = encodeNonAsciiUrl(
                    img?.attr("data-src")
                        ?.takeIf { it.isNotBlank() && !it.contains("src-default") }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                )
                val title = a.attr("title").ifBlank {
                    a.selectFirst("h3.title")?.text().orEmpty()
                }
                // v8 movies-only: genre pages mix series episodes in.
                if (!isMovieTitle(title)) continue
                movieCard(title, href, poster)?.let { out += it }
            }
            "myc" -> for (li in doc.select("ul#pm-grid li")) {
                val a = li.selectFirst("a[href*=watch.php]") ?: continue
                val href = a.attr("href")
                val title = a.attr("title")
                val img = li.selectFirst("img")
                val poster = img?.attr("data-echo")?.ifBlank { null }
                    ?: img?.attr("src")?.ifBlank { null }
                movieCard(title, href, poster)?.let { out += it }
            }
            "lar" -> for (article in doc.select("article.ay-card")) {
                val a = article.selectFirst("a.ay-card-media")
                    ?: article.selectFirst("a[href]") ?: continue
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val badge = article.selectFirst(".ay-card-badge")?.text().orEmpty()
                if (badge.isNotBlank() && !badge.contains("فيلم") &&
                    !badge.contains("نسخة")
                ) continue
                val title = article.selectFirst(".ay-card-title")?.text()
                    ?: a.attr("aria-label")
                val poster = article.selectFirst("img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("src") }
                }?.ifBlank { null }
                movieCard(title, href, poster)?.let { out += it }
            }
            "top" -> for (a in doc.select("a.recent--block")) {
                val href = a.attr("href")
                val title = a.attr("title")
                if (!isMovieTitle(title)) continue
                val img = a.selectFirst("img")
                val poster = img?.attr("data-src")?.ifBlank { null }
                    ?: img?.attr("src")?.ifBlank { null }
                val rating = a.selectFirst("li.imdbRating")?.text()?.trim()
                val shown = if (!rating.isNullOrBlank()) {
                    cleanMovieTitle(title) + " ★ $rating"
                } else {
                    title
                }
                movieCard(shown, href, poster)?.let { out += it }
            }
            "af" -> for (div in doc.select("div.ht_grid_1_4")) {
                val a = div.selectFirst("a.thumbnail-link") ?: continue
                val href = a.attr("href")
                val img = div.selectFirst("img")
                val poster = img?.attr("src")?.ifBlank { null }
                val title = div.selectFirst("h2.entry-title a")?.text().orEmpty()
                // v8 movies-only (af is films-only today, guard anyway).
                if (!isMovieTitle(title)) continue
                movieCard(title, href, poster)?.let { out += it }
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // v31: IMDb section — seed + resolver
    // ---------------------------------------------------------------
    /** v31: IMDb horror seed — the top of IMDb's horror interest
     * list (https://www.imdb.com/interest/horror/), extracted
     * 2026-11-23 via the real browser because IMDb answers plain
     * HTTP with an AWS WAF challenge (HTTP 202). Series on that
     * list are EXCLUDED (this addon is movies-only): Yellowjackets,
     * From, Supernatural, Crystal Lake, Stranger Things, The
     * Walking Dead, Widow's Bay, American Horror Story, The Last
     * of Us, It: Welcome to Derry, Monster (Netflix series).
     * "Hopeu" on the page is "Hope" (MovieBox's own ranking list
     * carries "Hope"). Title to IMDb rating; the card shows the
     * rating as "Title ★ rating". Refresh the seed when he asks. */
    private val imdbHorrorSeed = listOf(
        "The Body" to "7.4",
        "Obsession" to "7.8",
        "Resident Evil" to "7.4",
        "Carrie" to "7.8",
        "Other Mommy" to "5.3",
        "Clayface" to "7.5",
        "Backrooms" to "6.7",
        "Below" to "6.2",
        "Insidious: Out of the Further" to "6.0",
        "Hope" to "6.5",
        "Buddy" to "6.6",
        "Weapons" to "7.4",
        "Sinners" to "7.5",
        "418" to "7.6",
        "Teenage Sex and Death at Camp Miasma" to "6.4",
        "Ggoolalba" to "6.9",
        "Hokum" to "6.6",
        "Victorian Psycho" to "6.3",
        "The Cycle" to "6.3",
        "Apocalipsis Z: Nuevo Mundo" to "5.1",
        "The Last House" to "5.5",
        "Send Help" to "6.7",
        "X" to "6.5",
        "Frankenstein" to "7.4",
        "Gunche" to "6.5",
        "Dracula: A Love Tale" to "6.2",
        "The Lighthouse" to "7.3",
        "The Substance" to "7.2",
        "Cujo" to "6.1"
    )

    /** v31: normalized title key for IMDb matching — mbTitleKey
     * plus a trailing "(year)" and a "★ rating" suffix stripped,
     * so "Weapons (2025)" and "Weapons ★ 7.4" both key as
     * "weapons". Exact-key equality only: the MovieBox search is
     * fuzzy ("The Conjuring" also returns The Nun II), so a
     * startsWith match would resolve the wrong film. */
    private fun imdbKey(t: String): String {
        var s = t.trim()
        s = Regex("""\(\d{4}\)\s*$""").replace(s, "").trim()
        s = s.substringBefore("★").trim()
        return mbTitleKey(s)
    }

    /** v31: MovieBox H5 search for one IMDb title. Exact
     * normalized-title match only; among several years (remakes)
     * the latest release wins (the IMDb list is current). The
     * web token is cached 1 h by mbWebToken(), so per-title
     * calls are cheap. Returns the detail URL + poster, or null. */
    private suspend fun imdbSearchMovieBox(
        title: String
    ): Pair<String, String?>? {
        val token = runCatching { mbWebToken().first }.getOrNull().orEmpty()
        if (token.isBlank()) return null
        val body = JSONObject().apply {
            put("keyword", title)
            put("page", 1)
            put("perPage", 20)
            put("subjectType", 1)
        }.toString()
        val res = runCatching {
            app.post(
                "$mbWebBase/wefeed-h5api-bff/subject/search",
                headers = mapOf(
                    "User-Agent" to browserUA,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $token",
                    "X-MB-Token" to token
                ),
                requestBody = body.toRequestBody(
                    "application/json".toMediaType()
                )
            )
        }.getOrNull() ?: return null
        if (res.code !in 200..299) return null
        val items = runCatching {
            JSONObject(res.text).optJSONObject("data")?.optJSONArray("items")
        }.getOrNull() ?: return null
        val seedKey = imdbKey(title)
        var bestUrl: String? = null
        var bestPoster: String? = null
        var bestYear = -1
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optInt("subjectType", 1) != 1) continue
            val t = o.optString("title")
            if (t.isBlank()) continue
            if (imdbKey(t) != seedKey) continue
            val dp = o.optString("detailPath")
            if (dp.isBlank()) continue
            val y = mbFranchiseYear(o.optString("releaseDate"))
            if (y > bestYear) {
                bestYear = y
                bestUrl = "$mbWebBase/ar/moviedetail/$dp"
                bestPoster = o.optJSONObject("cover")?.optString("url")
                    ?.ifBlank { null }
                    ?: o.optString("poster").ifBlank { null }
                    ?: o.optString("coverUrl").ifBlank { null }
            }
        }
        val url = bestUrl ?: return null
        return url to bestPoster
    }

    /** v31: one HTML lane's search page for one IMDb title.
     * Same selectors as search(); the first card whose
     * normalized title exactly matches the seed wins.
     * Fail-fast fetch, null on any failure. */
    private fun imdbSearchLane(
        lane: String,
        title: String
    ): Pair<String, String?>? {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val url = when (lane) {
            "moviz" -> "https://www.moviz.land/?s=$q"
            "eg" -> "https://egy.bet/?s=$q"
            "myc" -> "https://vid.mycima.cc/search.php?keywords=$q"
            "lar" -> "https://larozatv.com/search?q=$q"
            "top" -> "https://topcinema.film/?s=$q"
            "af" -> "https://aflamstream.com/?s=$q"
            else -> return null
        }
        val html = httpGetTextHeaders(url, homeFetchTimeoutMs, browserHeaders)
            ?: return null
        val doc = runCatching { Jsoup.parse(html, url) }.getOrNull()
            ?: return null
        val seedKey = imdbKey(title)
        fun href(a: org.jsoup.nodes.Element): String =
            a.attr("abs:href").ifBlank { a.attr("href") }
        val found: Pair<String, String?>? = when (lane) {
            "moviz" -> doc.select(".search-card").firstNotNullOfOrNull { card ->
                val a = card.selectFirst("a[href]")
                    ?: return@firstNotNullOfOrNull null
                val img = card.selectFirst("img")
                val t = card.selectFirst(".search-card-title")?.text()
                    ?: img?.attr("alt") ?: a.attr("aria-label")
                if (!isMovieTitle(t) || imdbKey(t) != seedKey) {
                    return@firstNotNullOfOrNull null
                }
                href(a) to img?.attr("src")?.ifBlank { null }
            }
            "eg" -> doc.select("a.postBlock, a.postBlockCol")
                .firstNotNullOfOrNull { a ->
                    val img = a.selectFirst("img")
                    val poster = img?.attr("data-src")
                        ?.takeIf { it.isNotBlank() && !it.contains("src-default") }
                        ?: img?.attr("src")
                    val t = a.attr("title").ifBlank {
                        a.selectFirst("h3.title")?.text().orEmpty()
                    }
                    if (!isMovieTitle(t) || imdbKey(t) != seedKey) {
                        return@firstNotNullOfOrNull null
                    }
                    href(a) to poster?.ifBlank { null }
                }
            "myc" -> doc.select("ul#pm-grid li").firstNotNullOfOrNull { li ->
                val a = li.selectFirst("a[href*=watch.php]")
                    ?: return@firstNotNullOfOrNull null
                val img = li.selectFirst("img")
                val poster = img?.attr("data-echo")?.ifBlank { null }
                    ?: img?.attr("src")?.ifBlank { null }
                val t = a.attr("title")
                if (!isMovieTitle(t) || imdbKey(t) != seedKey) {
                    return@firstNotNullOfOrNull null
                }
                href(a) to poster
            }
            "lar" -> doc.select("article.ay-card").firstNotNullOfOrNull { article ->
                val a = article.selectFirst("a.ay-card-media")
                    ?: article.selectFirst("a[href]")
                    ?: return@firstNotNullOfOrNull null
                val badge = article.selectFirst(".ay-card-badge")?.text().orEmpty()
                if (badge.isNotBlank() && !badge.contains("فيلم") &&
                    !badge.contains("نسخة")
                ) return@firstNotNullOfOrNull null
                val t = article.selectFirst(".ay-card-title")?.text()
                    ?: a.attr("aria-label")
                if (!isMovieTitle(t) || imdbKey(t) != seedKey) {
                    return@firstNotNullOfOrNull null
                }
                val poster = article.selectFirst("img")?.let {
                    it.attr("abs:src").ifBlank { it.attr("src") }
                }?.ifBlank { null }
                href(a) to poster
            }
            "top" -> doc.select("a.recent--block").firstNotNullOfOrNull { a ->
                val t = a.attr("title")
                if (!isMovieTitle(t) || imdbKey(t) != seedKey) {
                    return@firstNotNullOfOrNull null
                }
                val img = a.selectFirst("img")
                val poster = img?.attr("data-src")?.ifBlank { null }
                    ?: img?.attr("src")?.ifBlank { null }
                href(a) to poster
            }
            "af" -> doc.select("div.ht_grid_1_4").firstNotNullOfOrNull { div ->
                val a = div.selectFirst("a.thumbnail-link")
                    ?: return@firstNotNullOfOrNull null
                val img = div.selectFirst("img")
                val t = div.selectFirst("h2.entry-title a")?.text().orEmpty()
                if (!isMovieTitle(t) || imdbKey(t) != seedKey) {
                    return@firstNotNullOfOrNull null
                }
                href(a) to img?.attr("src")?.ifBlank { null }
            }
            else -> null
        }
        val (u, p) = found ?: return null
        if (u.isBlank()) return null
        return u to p
    }

    /** v31: resolve one IMDb title to a playable card — MovieBox
     * search first, then the 6 HTML lanes in order, first hit
     * wins. Null when no source has it (the title is skipped). */
    private suspend fun imdbResolveTitle(
        title: String
    ): Pair<String, String?>? {
        runCatching { imdbSearchMovieBox(title) }.getOrNull()?.let { return it }
        // v32: FasselHD next — its panel API search is a real
        // catalog, stronger than scraping the HTML lanes.
        runCatching { imdbSearchFassel(title) }.getOrNull()?.let { return it }
        for (lane in listOf("moviz", "eg", "myc", "lar", "top", "af")) {
            runCatching { imdbSearchLane(lane, title) }.getOrNull()
                ?.let { return it }
        }
        return null
    }

    // ---------- v32: FasselHD (panel API, same source as the
    // FasselHD v13 addon) ----------
    private val fasApiPrimary = "https://kahitdgku.com/faselhd15/public/api"
    private val fasConfigUrl = "https://watchit.tn/api_urls.json"
    private var fasLiveBase: String? = null
    private var fasConfigTried = false
    private var fasConfigBase: String? = null

    /** GETs a path on the live FasselHD API base; body or null. */
    private suspend fun fasApiGet(path: String): String? {
        val bases = ArrayList<String>()
        fasLiveBase?.let { bases += it }
        if (fasApiPrimary !in bases) bases += fasApiPrimary
        if (!fasConfigTried) {
            fasConfigTried = true
            fasConfigBase = runCatching {
                val cfg = app.get(fasConfigUrl, headers = browserHeaders).text
                JSONObject(cfg).optString("backupApiUrlNoureddine").trim().trimEnd('/')
            }.getOrNull()?.ifBlank { null }
        }
        fasConfigBase?.let { if (it !in bases) bases += it }
        for (base in bases) {
            val res = runCatching { app.get("$base/$path", headers = browserHeaders) }.getOrNull()
            val text = res?.text
            if (res != null && res.code == 200 && text != null &&
                (text.startsWith("{") || text.startsWith("["))
            ) {
                fasLiveBase = base
                return text
            }
        }
        return null
    }

    private suspend fun fasApiJson(path: String): JSONObject? {
        val text = fasApiGet(path) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** v32: one IMDb title on FasselHD — panel search, movie
     * rows only, exact normalized-title match, first hit wins
     * (same contract as imdbSearchLane). Returns the detail
     * URL (a "movie:<id>" token on fasselhd.com, which load()
     * and loadLinksFassel understand) + poster. Verified live
     * 2026-11-23: «Weapons» returns the 2025 film first
     * (id 29099) — the title MovieBox lacks. */
    private suspend fun imdbSearchFassel(
        title: String
    ): Pair<String, String?>? {
        val q = java.net.URLEncoder.encode(title, "UTF-8")
        val root = fasApiJson("search/$q/1") ?: return null
        val rows = root.optJSONArray("search") ?: return null
        val seedKey = imdbKey(title)
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("type").lowercase() != "movie") continue
            val t = row.optString("title").ifBlank { row.optString("name") }
            if (t.isBlank() || !isMovieTitle(t) || imdbKey(t) != seedKey) continue
            val id = row.optString("id")
            if (id.isBlank()) continue
            return "https://fasselhd.com/movie:$id" to
                row.optString("poster_path").ifBlank { null }
        }
        return null
    }

    /** v32: the videos "header" field — a bare URL is the
     * referer; otherwise "Key: Value|Key: Value" pairs. */
    private fun fasParseHeaderField(raw: String): Pair<String?, Map<String, String>> {
        val t = raw.trim()
        if (t.isBlank()) return null to emptyMap()
        if (t.startsWith("http")) return t to emptyMap()
        val extra = LinkedHashMap<String, String>()
        if (t.contains(":")) {
            for (part in t.split("|")) {
                val kv = part.split(":", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim()
                    val v = kv[1].trim()
                    if (k.isNotBlank() && v.isNotBlank() &&
                        k.all { it.isLetterOrDigit() || it == '-' }
                    ) extra[k] = v
                }
            }
        }
        return null to extra
    }

    private fun fasIsDirect(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4")
    }

    /** v32: FasselHD playback — detail -> videos -> stock
     * extractors; direct .mp4/.m3u8 emitted as-is. The
     * FasselHD addon's probe/dig/relay phases are NOT ported
     * — a server no stock extractor handles yields nothing. */
    private suspend fun loadLinksFassel(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val id = Regex("""movie:(\d+)""").find(data)?.groupValues?.get(1)
        if (id.isNullOrBlank()) {
            emitDebug(listOf("Horror·Fassel", "id-missing"), callback)
            return true
        }
        val root = fasApiJson("media/detail/$id/1")
        val videos = root?.optJSONArray("videos")
        if (videos == null || videos.length() == 0) {
            emitDebug(listOf("Horror·Fassel", "no-videos"), callback)
            return true
        }
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        for (i in 0 until videos.length()) {
            if (emitted >= 6 || elapsed() > 25000) break
            val v = videos.optJSONObject(i) ?: continue
            val link = v.optString("link")
            if (link.isBlank()) continue
            val server = v.optString("server").ifBlank { "FasselHD" }
            val (ref, _) = fasParseHeaderField(v.optString("header"))
            val referer = ref ?: "https://fasselhd.com/"
            if (fasIsDirect(link)) {
                emitDirectLabeled("FasselHD $server", link, referer, counting)
            } else {
                runCatching {
                    loadExtractor(link, referer, subtitleCallback, counting)
                }
            }
        }
        if (emitted == 0) {
            emitDebug(
                listOf(
                    "Horror·Fassel", "srv=${videos.length()}",
                    "ext=0", "t=${elapsed() / 1000}s"
                ),
                callback
            )
        }
        return true
    }

    // ---------------------------------------------------------------
    // Search (all four lanes, capped per lane)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        val jobs = listOf(
            "https://www.moviz.land/?s=$q" to "moviz",
            "https://egy.bet/?s=$q" to "eg",
            "https://vid.mycima.cc/search.php?keywords=$q" to "myc",
            "https://larozatv.com/search?q=$q" to "lar",
            "https://topcinema.film/?s=$q" to "top",
            "https://aflamstream.com/?s=$q" to "af"
        )
        for ((url, lane) in jobs) {
            runCatching {
                val doc = app.get(url, headers = browserHeaders).document
                val cards = when (lane) {
                    "moviz" -> doc.select(".search-card").mapNotNull { card ->
                        val a = card.selectFirst("a[href]") ?: return@mapNotNull null
                        val img = card.selectFirst("img")
                        val title = card.selectFirst(".search-card-title")?.text()
                            ?: img?.attr("alt") ?: a.attr("aria-label")
                        movieCard(title, a.attr("href"), img?.attr("src"))
                    }
                    "eg" -> doc.select("a.postBlock, a.postBlockCol").mapNotNull { a ->
                        val img = a.selectFirst("img")
                        val poster = img?.attr("data-src")
                            ?.takeIf { it.isNotBlank() && !it.contains("src-default") }
                            ?: img?.attr("src")
                        val title = a.attr("title").ifBlank {
                            a.selectFirst("h3.title")?.text().orEmpty()
                        }
                        movieCard(title, a.attr("href"), poster)
                    }
                    "myc" -> doc.select("ul#pm-grid li").mapNotNull { li ->
                        val a = li.selectFirst("a[href*=watch.php]") ?: return@mapNotNull null
                        val img = li.selectFirst("img")
                        val poster = img?.attr("data-echo")?.ifBlank { null }
                            ?: img?.attr("src")?.ifBlank { null }
                        movieCard(a.attr("title"), a.attr("href"), poster)
                    }
                    "lar" -> doc.select("article.ay-card").mapNotNull { article ->
                        val a = article.selectFirst("a.ay-card-media")
                            ?: article.selectFirst("a[href]") ?: return@mapNotNull null
                        val badge = article.selectFirst(".ay-card-badge")?.text().orEmpty()
                        if (badge.isNotBlank() && !badge.contains("فيلم") &&
                            !badge.contains("نسخة")
                        ) return@mapNotNull null
                        val title = article.selectFirst(".ay-card-title")?.text()
                            ?: a.attr("aria-label")
                        val poster = article.selectFirst("img")?.let {
                            it.attr("abs:src").ifBlank { it.attr("src") }
                        }?.ifBlank { null }
                        movieCard(title, a.attr("abs:href").ifBlank { a.attr("href") }, poster)
                    }
                    "top" -> doc.select("a.recent--block").mapNotNull { a ->
                        val title = a.attr("title")
                        if (!isMovieTitle(title)) return@mapNotNull null
                        val img = a.selectFirst("img")
                        val poster = img?.attr("data-src")?.ifBlank { null }
                            ?: img?.attr("src")?.ifBlank { null }
                        val rating = a.selectFirst("li.imdbRating")?.text()?.trim()
                        val shown = if (!rating.isNullOrBlank()) {
                            cleanMovieTitle(title) + " ★ $rating"
                        } else {
                            title
                        }
                        movieCard(shown, a.attr("href"), poster)
                    }
                    "af" -> doc.select("div.ht_grid_1_4").mapNotNull { div ->
                        val a = div.selectFirst("a.thumbnail-link") ?: return@mapNotNull null
                        val img = div.selectFirst("img")
                        val title = div.selectFirst("h2.entry-title a")?.text().orEmpty()
                        movieCard(title, a.attr("href"), img?.attr("src"))
                    }
                    else -> emptyList()
                }
                out += cards.filter { isMovieTitle(it.name) }.take(12)
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------
    private fun og(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=og:$prop]")?.attr("content")
            ?.trim()?.ifBlank { null }

    // ---------------------------------------------------------------
    // Trailer (v13, user order 2026-11-14 — applies to EVERY title
    // in the addon, on the detail page)
    // ---------------------------------------------------------------
    /**
     * Trailer lookup: YouTube search (free, no key) with the desktop
     * UA — the mobile search page returns no parseable results, the
     * desktop page carries ytInitialData with videoId + title runs.
     * v4 (2026-11-15, user order): trailers in the ORIGINAL language only (dubbed/localized variants skipped).
     * An Arabic-only title gets the poster only — no YouTube query
     * at all. Latin candidates only, valid only if they contain at
     * least one [A-Za-z] letter (digits-only candidates like "2"
     * are NOT titles); a result is accepted only when its title
     * contains a Latin candidate AND a trailer word.
     * IMDb's own title page is 202-blocked from datacenter IPs, so
     * IMDb is the title resolver only (the subtitle gear's
     * resolveImdbId) — not the video host. The app shows its
     * trailer button from LoadResponse.trailers; a missing button
     * means the search found nothing for that title. Returns an
     * empty list on any failure — a missing trailer must never
     * break the detail page. NOTE: the setter expects a
     * MutableList (stub-verified), so this returns MutableList,
     * not List. Movie-only module: no isSeries / acceptPv params.
     */


    // v4 (2026-11-15, user order): trailers in the ORIGINAL language
    // only — dubbed/localized variants (French/Polish/Russian/...) are
    // skipped. v3 rules still hold: an Arabic-only title gets the poster
    // only — no YouTube query at all; Latin candidates only, valid only
    // if they contain at least one [A-Za-z] letter. The pick prefers the
    // exact "official trailer" first, then any trailer/teaser/PV — and
    // rejects titles carrying dub markers or non-Latin script (Cyrillic/
    // Arabic/CJK), which are dubs or localized re-uploads, never the
    // original. No match = no trailer button.

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

    /**
     * One YouTube search: the first videoId whose nearby title
     * (case-insensitive) contains a Latin candidate AND a trailer
     * word (trailer / teaser / PV-if-anime). Results
     * with no readable nearby title are skipped. Null when
     * nothing matches — no blind first-result fallback. Each
     * videoId is paired with the title run that follows it in the
     * same renderer (within 1200 chars), not by zipping two global
     * lists — the page repeats ids across sections, so global
     * zipping can misalign.
     */
    // v4 (2026-11-15): the pick skips dubbed/localized results
    // entirely (see isDubbedTitle / hasNonLatinScript below) and
    // prefers the exact "official trailer" in a first pass.
    private suspend fun youtubeTrailerId(
        query: String,
        names: List<String>,
        acceptPv: Boolean = false
    ): String? {
        return try {
            val url = "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val html = app.get(url, headers = browserHeaders).text
            if (html.isBlank()) return null
            val idRe = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
            val runsRe =
                Regex(""""title":\{"runs":\[\{"text":"([^"]{3,120})"""")
            val simpleRe =
                Regex(""""title":\{"simpleText":"([^"]{3,120})"""")
            val lcNames = names.map { it.lowercase(java.util.Locale.ROOT) }
                .filter { it.length >= 3 }
            if (lcNames.isEmpty()) return null
            // Dub / localized markers: a result title carrying one of
            // these is a dubbed or localized re-upload, never the
            // original-language trailer. "dub" is a WHOLE WORD only
            // (word-boundary regex) and "dubbed" is listed separately,
            // so words like "club" or "Dubai" can never match.
            // "doblado"/"doppiato" = dubbed in Spanish/Italian;
            // "vostfr" = French-subtitled version; "oficial"/
            // "legendado" = Portuguese localized title.
            val dubbedMarks = listOf(
                "dubbed", "dubs", "doublé", "doublage", "dublado",
                "dublagem", "dublat", "doblado", "doppiato", "zwiastun",
                "bande-annonce", "bande annonce", "trailer officiel",
                "officiel", "oficial", "polski", "français", "francais",
                "russian", "русский", "трейлер", "дубляж", "озвучка",
                "hindi", "tamil", "telugu", "español", "italiano",
                "deutsch", "português", "türkçe", "legendado", "vostfr",
                "in french", "in polish", "in russian"
            )
            val dubWordRe = Regex("""\bdub\b""")
            val isDubbedTitle = { t: String ->
                val lt = t.lowercase(java.util.Locale.ROOT)
                dubWordRe.containsMatchIn(lt) ||
                    dubbedMarks.any { lt.contains(it) }
            }
            // Non-Latin script in the title = a dub or a localized
            // re-title, never the original (original-language titles
            // for our Latin-named films are Latin-script).
            val hasNonLatinScript = { t: String ->
                t.any { c ->
                    (c in '\u0400'..'\u052F') || (c in '\u0600'..'\u06FF') ||
                    (c in '\u4E00'..'\u9FFF') || (c in '\u3040'..'\u30FF') ||
                    (c in '\u0590'..'\u05FF')
                }
            }
            val seen = HashSet<String>()
            val cands = ArrayList<Pair<String, String>>()
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
                if (isDubbedTitle(t)) continue
                if (hasNonLatinScript(t)) continue
                cands += id to t
            }
            // Pass 1: the exact "official trailer" first.
            for ((id, t) in cands) {
                if (t.lowercase(java.util.Locale.ROOT)
                    .contains("official trailer")) return id
            }
            // Pass 2: any trailer / teaser / PV.
            for ((id, t) in cands) {
                val lt = t.lowercase(java.util.Locale.ROOT)
                if (lt.contains("trailer") || lt.contains("teaser") ||
                    (acceptPv && t.contains("PV"))) return id
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("moviz.land") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(
                    og(doc ?: return movieFallback(url), "title") ?: ""
                )
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val rating = doc?.let {
                    Regex("""class="rating"[^>]*>\s*<i[^>]*></i>\s*([\d.]+)/10""")
                        .find(it.html())?.groupValues?.get(1)
                }
                val plot = buildString {
                    if (rating != null) append("★ التقييم: $rating/10\n")
                    if (!desc.isNullOrBlank()) append(desc)
                }.ifBlank { null }
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "MovizLand" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = plot
                    this.trailers = trailers
                }
            }
            isEgyBest(url) -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { encodeNonAsciiUrl(og(it, "image")) }
                val desc = doc?.let { og(it, "description") }
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "EgyBest" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                    this.trailers = trailers
                }
            }
            url.contains("mycima") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                    ?: doc?.selectFirst("img[src*=uploads]")?.attr("src")
                val desc = doc?.let { og(it, "description") }
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "MyCima" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                    this.trailers = trailers
                }
            }
            url.contains("larozatv") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val slug = url.trimEnd('/').substringAfterLast('/')
                val servers = "https://larozatv.com/watch/$slug/servers"
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "LaRosTV" }, url, TvType.Movie, subData(servers, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                    this.trailers = trailers
                }
            }
            url.contains("topcinema") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val watch = url.trimEnd('/') + "/watch/"
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "TopCinema" }, url, TvType.Movie, subData(watch, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                    this.trailers = trailers
                }
            }
            url.contains("aflamstream") -> {
                val doc = runCatching {
                    app.get(url, headers = browserHeaders).document
                }.getOrNull()
                val title = cleanMovieTitle(og(doc ?: return movieFallback(url), "title") ?: "")
                val poster = doc?.let { og(it, "image") }
                val desc = doc?.let { og(it, "description") }
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "AflamStream" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = desc
                    this.trailers = trailers
                }
            }
            // v17: MovieBox — detail JSON; fields live under
            // data.subject (NOT data itself). subjectId is packed
            // into the loadLinks data string (third "|||" part).
            url.contains("moviebox.sg") || url.contains("movie-box.co") -> {
                val dp = url.substringAfter("/moviedetail/")
                    .substringBefore("?").substringBefore("#").trim()
                val json = runCatching {
                    app.get(
                        "$mbWebBase/wefeed-h5api-bff/detail?detailPath=" +
                            java.net.URLEncoder.encode(dp, "UTF-8"),
                        headers = mbJsonHeaders
                    ).text
                }.getOrNull()
                val subj = runCatching {
                    JSONObject(json ?: "").optJSONObject("data")
                        ?.optJSONObject("subject")
                }.getOrNull()
                if (subj == null) return movieFallback(url)
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
                val releaseDate = subj.optString("releaseDate")
                val trailers = trailerFor(title)
                // v17: franchise parts — every part of the series,
                // ordered, on the film's own page (fail-soft; no
                // row at all when no other part is found).
                val parts = mbFranchiseParts(
                    title, dp, releaseDate, poster
                )
                newMovieLoadResponse(
                    title.ifBlank { "MovieBox" }, url, TvType.Movie,
                    movieBoxData(url, title, subjectId)
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = plot
                    this.trailers = trailers
                    if (parts.size > 1) this.recommendations = parts
                }
            }
            // v32: FasselHD — panel API detail; the URL carries
            // a "movie:<id>" token.
            url.contains("fasselhd.com") -> {
                val id = Regex("""movie:(\d+)""").find(url)?.groupValues?.get(1)
                    ?: return movieFallback(url)
                val root = fasApiJson("media/detail/$id/1")
                    ?: return movieFallback(url)
                val title = cleanMovieTitle(
                    root.optString("title").ifBlank { root.optString("original_name") }
                )
                val poster = root.optString("poster_path").ifBlank { null }
                val desc = root.optString("overview").ifBlank { null }
                val year = Regex("""(19|20)\d\d""")
                    .find(root.optString("release_date"))?.value?.toIntOrNull()
                val trailers = trailerFor(title)
                newMovieLoadResponse(title.ifBlank { "فاصل إتش دي" }, url, TvType.Movie, subData(url, title)) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = root.optString("backdrop_path").ifBlank { null }
                    this.plot = desc
                    this.year = year
                    this.trailers = trailers
                }
            }
            else -> movieFallback(url)
        }
    }

    private suspend fun movieFallback(url: String): LoadResponse {
        // v15: trim the trailing slash first — a detail URL ending
        // in "/" gave substringAfterLast('/') = "" -> blank name.
        val name = cleanMovieTitle(url.trimEnd('/').substringAfterLast('/').replace('-', ' '))
        return newMovieLoadResponse(name.ifBlank { "فيلم" }, url, TvType.Movie, subData(url, name)) {
            this.trailers = trailerFor(name)
        }
    }

    // ---------------------------------------------------------------
    // Playback — shared machinery
    // ---------------------------------------------------------------
    private fun fixUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "https://" + u.trimStart('/')
        else -> u
    }

    private fun asciiSafe(url: String): String {
        if (url.all { it.code < 128 }) return url
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 128 && (c.isLetterOrDigit() || c in "-._~:/?#[]@!$&'()*+,;=%")) {
                sb.append(c)
            } else {
                sb.append('%').append(v.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    private fun hostOf(url: String): String =
        Regex("""^[a-z]+://([^/]+)""").find(url)?.groupValues?.get(1)
            ?.lowercase() ?: ""

    /** v15: EgyBest moved domains twice in 24 h (egibest.life ->
     * egibest.vip -> egy.bet, 2026-11-15). Match by host so the
     * next move only needs one line here — a hard-coded
     * contains("egibest") broke load() AND loadLinks() the moment
     * the site became egy.bet (no "egibest" substring anywhere). */
    private fun isEgyBest(url: String): Boolean {
        val h = hostOf(url)
        return h.contains("egibest") || h == "egy.bet" || h.endsWith(".egy.bet")
    }

    /** v15 (defensive): percent-encode non-ASCII chars in a URL
     * (EgyBest posters carry raw Arabic in the filename).
     * ASCII URLs pass through unchanged; existing %XX sequences
     * are left alone (only chars > 127 are touched). */
    private fun encodeNonAsciiUrl(u: String?): String? {
        if (u.isNullOrBlank()) return u
        val sb = StringBuilder()
        for (ch in u) {
            if (ch.code < 128) sb.append(ch)
            else for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                sb.append('%')
                    .append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    private fun decodeBase64(input: String): String? {
        val clean = input.trim().replace('-', '+').replace('_', '/')
            .filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = table.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return runCatching {
            String(out.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun extractDirect(text: String): String? {
        val clean = text.replace("\\/", "/")
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(clean)?.let {
            return it.groupValues[1]
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").find(clean)?.let {
            return it.groupValues[1]
        }
        // v14: MixDrop packed pages carry MDCore.wurl="//host/...mp4"
        // (protocol-relative) — the stock extractor reads wurl, the
        // dig never did, so the one live MixDrop mirror was missed.
        Regex("""wurl\s*[:=]\s*["']([^"']+)["']""").find(clean)?.let {
            val u = it.groupValues[1]
            if (u.contains(".mp4") || u.contains(".m3u8")) {
                normalizeMediaUrl(u)?.let { return it }
            }
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(clean)?.let {
            return it.value
        }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(clean)?.let {
            return it.value
        }
        // v14: protocol-relative media URLs (//host/...mp4|m3u8).
        Regex("""//[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""").find(clean)?.let {
            return "https:" + it.value
        }
        return null
    }

    /** v14: "//host/..." -> "https://host/..."; http(s) URLs pass through. */
    private fun normalizeMediaUrl(u: String): String? = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http") -> u
        else -> null
    }

    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

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
            out += Regex("""\b\w+\b""").replace(payload) { mr ->
                map[mr.value] ?: mr.value
            }
        }
        return out
    }

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

    private suspend fun resolveViaRelay(
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val relayUrl = "$relayBase/relay/resolve?url=" +
            java.net.URLEncoder.encode(embed, "UTF-8") +
            "&ref=" + java.net.URLEncoder.encode(data, "UTF-8")
        // v14: ONE attempt, hard 8 s cap (raw HttpURLConnection).
        // The old loop did 2 unbounded app.get attempts with a 2 s
        // sleep — on a sleeping Render service that burned ~100 s
        // (on-device Dead Howling: t=117s) for zero links.
        val text = httpGetText(relayUrl, 8000) ?: return 0
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return 0
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

    private suspend fun digAllForDirects(
        url: String,
        referer: String?
    ): List<String> {
        val doc = runCatching {
            val h = LinkedHashMap<String, String>()
            h["User-Agent"] = browserUA
            if (!referer.isNullOrBlank()) h["Referer"] = asciiSafe(referer)
            app.get(url, headers = h).text
        }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        out += extractAllDirects(doc)
        if (out.isEmpty()) {
            for (unpacked in unpackPackedJs(doc)) {
                out += extractAllDirects(unpacked)
                if (out.isNotEmpty()) break
            }
        }
        return out.take(3)
    }

    private fun extractAllDirects(text: String): List<String> {
        val clean = text.replace("\\/", "/")
        val out = LinkedHashSet<String>()
        fun add(u: String) {
            val t = u.trimEnd('\\')
            if (t.isNotBlank()) out += t
        }
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(clean).forEach {
            add(it.groupValues[1])
        }
        Regex("""videoUrl\s*=\s*"(https?://[^"]+)"""").findAll(clean).forEach {
            add(it.groupValues[1])
        }
        // v14: wurl + protocol-relative media URLs (see extractDirect).
        Regex("""wurl\s*[:=]\s*["']([^"']+)["']""").findAll(clean).forEach {
            val u = it.groupValues[1]
            if (u.contains(".mp4") || u.contains(".m3u8")) {
                normalizeMediaUrl(u)?.let { add(it) }
            }
        }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(clean).forEach {
            add(it.value)
        }
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(clean).forEach {
            add(it.value)
        }
        Regex("""//[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""").findAll(clean).forEach {
            add("https:" + it.value)
        }
        return out.take(4)
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            callback(
                newExtractorLink(
                    name, name, url,
                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitDirectLabeled(
        label: String,
        directUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = LinkedHashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = asciiSafe(referer)
        headers["User-Agent"] = browserUA
        runCatching {
            callback(
                newExtractorLink(
                    name, label, directUrl,
                    if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer?.let { asciiSafe(it) } ?: ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

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

    // ---------------------------------------------------------------
    // Playback — per-lane chains, all on a wall-clock budget
    // ---------------------------------------------------------------
    private val priorityHosts = listOf(
        "mixdrop", "hgcloud", "streamhg", "vidmoly", "byse", "doodstream",
        "streamwish", "streamtape", "uqload", "voe", "filemoon", "vidhide", "earnvids"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val t0 = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - t0
        // v32: subtitle gear, three modes (ar/en/dual, default
        // dual) — OpenSubtitles Arabic + English, merged for
        // dual. Applies to every film, IMDb section included.
        val (realData, subTitle, _) = parseSubData(data)
        if (subTitle.isNotBlank()) {
            emitSubs(subTitle, subtitleCallback)
        }
        return when {
            realData.contains("moviz.land") -> loadLinksMoviz(realData, subtitleCallback, callback, ::elapsed)
            isEgyBest(realData) -> loadLinksEgyBest(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("mycima") -> loadLinksMyCima(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("larozatv") -> loadLinksLaRos(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("topcinema") -> loadLinksTop(realData, subtitleCallback, callback, ::elapsed)
            realData.contains("aflamstream") -> loadLinksAf(realData, subtitleCallback, callback, ::elapsed)
            // v32: FasselHD — the URL carries a "movie:<id>" token.
            realData.contains("fasselhd.com") -> loadLinksFassel(realData, subtitleCallback, callback, ::elapsed)
            // v17: MovieBox — full data string (carries subjectId).
            isMovieBox(realData) -> loadLinksMovieBox(data, subtitleCallback, callback, ::elapsed)
            else -> {
                emitDebug(listOf("Horror", "unknown-lane"), callback)
                true
            }
        }
    }

    /** v14: stock-extractor coverage, verified against the app's
     * extractor registry (stub mainUrls). Hosts NOT in this set can
     * never resolve via loadExtractor — they only resolve via the
     * dig (packed-JS / direct-link) path or the relay. EgyBest's
     * template serves many such hosts (vidmoly.org, uqload.vc,
     * mixdrop.top, 1vid, vidspeed, cybervynx, byse mirrors). */
    private val extractorHosts = setOf(
        "mixdrop.co", "mixdrop.ag", "mixdrop.bz", "mixdrop.ch",
        "mixdrop.ps", "mixdrop.si", "mixdrop.to",
        "voe.sx", "uqload.com", "uqload.co", "uqload.bz", "uqload.cx",
        "uqloads.xyz", "vidmoly.net", "vidmoly.biz", "vidmoly.me",
        "vidmoly.to", "byse.sx", "bysebuho.com", "byseqekaho.com",
        "bysevepoin.com", "bysezejataos.com", "hgcloud.to",
        "doodstream.com", "dood.cx", "dood.la", "dood.li", "dood.pm",
        "dood.sh", "dood.so", "dood.to", "dood.watch", "dood.wf",
        "dood.ws", "dood.yt", "streamwish.to", "streamwish.site",
        "streamtape.com", "streamtape.net", "streamtape.xyz",
        "filemoon.to", "filemoon.in", "filemoon.sx",
        "filelions.to", "filelions.live", "filelions.online",
        "vidhide.com", "vidhidehub.com", "vidhidepre.com",
        "vidhidepro.com", "vidhidevip.com", "mp4upload.com",
        "firestream.to", "firestream.site",
        "lulustream.com", "luluvdo.com", "luluvdoo.com"
    )

    private fun hasStockExtractor(url: String): Boolean {
        val h = hostOf(url)
        if (h.isBlank()) return false
        return extractorHosts.any { h == it || h.endsWith(".$it") }
    }

    /** v14: hosts whose pages most often yield a direct/packed link
     * when dug — tried first in the dig phases. */
    private val digFirstHosts = listOf(
        "vidmoly", "1vid", "vidspeed", "uqload", "mixdrop",
        "mp4upload", "nuvorel", "luluv", "playmogo", "streamtape",
        "filemoon"
    )

    private fun digRank(url: String): Int {
        val h = hostOf(url)
        return if (digFirstHosts.any { h.contains(it) }) 0 else 1
    }

    /** Shared extractor → dig → relay core with caps and a budget.
     * The caps are measured from THIS chain's own clock, not from
     * loadLinks entry (v10: a slow earlier stage ate the whole
     * budget before a single extractor ran).
     * v14 restructure (Dead Howling 2026 on-device: srv=17 ext=0
     * dig=0 relay=0 t=117s): the old order ran extractors first
     * on a priority-sorted list, so six DEAD mixdrop.ps embeds ate
     * the 11 s extractor budget and the ~5 s of dig budget left
     * died on the same dead hosts before vidmoly / 1vid / vidspeed
     * / uqload / mixdrop.top were ever tried — all five yield a
     * real direct link when dug (verified live on three titles).
     * Then the relay phase ran unbounded app.get calls against the
     * sleeping Render service and burned ~100 s for zero links.
     * Now: dig-first for no-extractor hosts (D1), then extractors
     * capped at 2 per host (E), then a second dig pass (D2), then
     * ONE bounded relay attempt (R). Total chain budget ≈ 18 s
     * plus one 8 s relay attempt, worst case. */
    private suspend fun runChain(
        embeds: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long,
        lane: String,
        allowDebug: Boolean = true,
        diagExtra: List<String> = emptyList()
    ): Boolean {
        val tChain = System.currentTimeMillis()
        val chainMs = { System.currentTimeMillis() - tChain }
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        var extOk = 0
        var digOk = 0
        val digTried = HashSet<String>()
        val digHostTried = HashMap<String, Int>()
        suspend fun digOne(embed: String): Boolean {
            digTried += embed
            val h = hostOf(embed)
            digHostTried[h] = (digHostTried[h] ?: 0) + 1
            return scanEmbed(embed, referer, counting).also {
                if (it) digOk++
            }
        }
        fun digHostCapped(embed: String): Boolean {
            val h = hostOf(embed)
            return (digHostTried[h] ?: 0) < 2
        }
        // Phase D1 — dig the no-extractor hosts first (cap 9 s,
        // max 3 digs). These can never resolve via loadExtractor.
        val (digEmbeds, extEmbeds) = embeds.partition { !hasStockExtractor(it) }
        val digOrder = digEmbeds.sortedWith(
            compareBy({ digRank(it) }, { digEmbeds.indexOf(it) })
        )
        for (embed in digOrder) {
            if (emitted >= 5 || digOk >= 3 || chainMs() > 9000) break
            if (!digHostCapped(embed)) continue
            digOne(embed)
        }
        // Phase E — stock extractors (cap 14 s, max 2 per host:
        // six copies of one dead host must not eat the budget).
        val extHostTried = HashMap<String, Int>()
        val orderedExt = extEmbeds.sortedByDescending { e ->
            priorityHosts.any { e.contains(it) }
        }
        val unresolved = mutableListOf<String>()
        for (embed in orderedExt) {
            if (emitted >= 5 || chainMs() > 14000) break
            val h = hostOf(embed)
            if ((extHostTried[h] ?: 0) >= 2) continue
            extHostTried[h] = (extHostTried[h] ?: 0) + 1
            val before = emitted
            runCatching { loadExtractor(embed, referer, subtitleCallback, counting) }
            if (emitted > before) extOk++ else unresolved += embed
        }
        // Phase D2 — dig whatever was never dug (cap 18 s).
        if (emitted < 3) {
            val rest = embeds.filter { it !in digTried }.sortedWith(
                compareBy({ digRank(it) }, { embeds.indexOf(it) })
            )
            for (embed in rest) {
                if (emitted >= 5 || digOk >= 3 || chainMs() > 18000) break
                if (!digHostCapped(embed)) continue
                digOne(embed)
            }
        }
        // Phase R — relay, last resort only: ONE embed, ONE bounded
        // attempt (v14: the old 2-attempt app.get loop hung ~100 s
        // on a sleeping Render service).
        var relayOk = 0
        if (emitted == 0 && chainMs() <= 12000) {
            val relayPick = (unresolved + embeds.filter { it !in digTried })
                .distinct()
                .sortedWith(compareBy({ digRank(it) }, { embeds.indexOf(it) }))
                .firstOrNull()
            if (relayPick != null) {
                relayOk += resolveViaRelay(relayPick, referer, counting)
            }
        }
        if (emitted == 0 && allowDebug) {
            emitDebug(
                listOf("Horror v21·$lane") + diagExtra +
                    listOf("srv=${embeds.size}", "ext=$extOk",
                        "dig=$digOk", "relay=$relayOk", "t=${elapsed() / 1000}s"),
                callback
            )
        }
        return true
    }

    private suspend fun loadLinksMoviz(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val watchUrl = data.trimEnd('/') + "/watch/"
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·Moviz", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashSet<String>()
        val upbom = mutableListOf<String>()
        for (card in doc.select(".server-card")) {
            val src = card.attr("data-src")
            if (!src.startsWith("http")) continue
            if (hostOf(src).contains("upbom")) upbom += src else embeds += src
        }
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        // upbom direct mp4s first (fast, own CDN).
        for (embed in upbom) {
            val text = runCatching {
                app.get(embed, headers = browserHeaders, referer = watchUrl).text
            }.getOrNull() ?: continue
            val byQ = LinkedHashMap<String, String>()
            Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").findAll(text).forEach { m ->
                val u = m.value.trimEnd('\\')
                val q = Regex("""(360|480|720|1080)p""").find(u)
                    ?.groupValues?.get(1) ?: "HD"
                byQ.putIfAbsent(q, u)
            }
            for ((q, u) in byQ) {
                emitDirectLabeled("موفيز لاند ${q}p", u, embed, counting)
            }
        }
        if (emitted >= 2) return true
        // Remaining servers through the shared chain (extractors/digs/relay).
        val rest = embeds.toList()
        if (rest.isNotEmpty()) {
            runChain(rest, watchUrl, subtitleCallback, counting, elapsed, "Moviz",
                allowDebug = emitted == 0)
        } else if (emitted == 0) {
            emitDebug(listOf("Horror·Moviz", "srv=0"), callback)
        }
        return true
    }

    private suspend fun loadLinksEgyBest(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val res = runCatching { app.get(data, headers = browserHeaders) }.getOrNull()
        if (res == null) {
            emitDebug(listOf("Horror v21·EgyBest", "page-fail"), callback)
            return true
        }
        val text = res.text
        val embeds = LinkedHashSet<String>()
        Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let {
            if (it.startsWith("http")) embeds += fixUrl(it)
        }
        Regex("""loadIframe\(\s*this\s*,\s*'([^']+)'\s*\)""").findAll(text).forEach { m ->
            val wrapper = m.groupValues[1]
            val encoded = wrapper.substringAfter("url=", "")
            if (encoded.isNotBlank()) {
                val decoded = decodeBase64(
                    runCatching {
                        java.net.URLDecoder.decode(encoded, "UTF-8")
                    }.getOrDefault(encoded)
                )
                if (decoded != null && decoded.startsWith("http")) {
                    embeds += fixUrl(decoded)
                } else if (wrapper.startsWith("http") &&
                    !isEgyBest(wrapper)
                ) {
                    embeds += fixUrl(wrapper)
                }
            }
        }
        res.document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !isEgyBest(src)) {
                embeds += fixUrl(src)
            }
        }
        return runChain(embeds.toList(), data, subtitleCallback, callback, elapsed, "EgyBest")
    }

    private suspend fun loadLinksMyCima(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val vid = Regex("""vid=([a-z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid == null) {
            emitDebug(listOf("Horror·MyCima", "vid-missing"), callback)
            return true
        }
        val playUrl = "https://vid.mycima.cc/play.php?vid=$vid"
        val res = runCatching {
            app.get(playUrl, headers = browserHeaders, referer = data)
        }.getOrNull()
        if (res == null) {
            emitDebug(listOf("Horror·MyCima", "play-fail"), callback)
            return true
        }
        val candidates = LinkedHashSet<String>()
        res.document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("mycima")) {
                candidates += fixUrl(src)
            }
        }
        res.document.select("a[href]").forEach {
            val href = it.attr("href").trim()
            if (href.startsWith("http") && !href.contains("mycima") &&
                (href.contains("/e/") || href.contains("/embed"))
            ) candidates += fixUrl(href)
        }
        Regex("""https?://[^\s"'<>\\]+""").findAll(res.text).forEach { m ->
            val u = m.value
            if (!u.contains("mycima") && (u.contains("/e/") || u.contains("/embed")) &&
                !u.contains("google") && !u.contains("facebook") &&
                !u.contains(".js") && !u.contains(".css") &&
                !u.contains(".png") && !u.contains(".jpg")
            ) candidates += fixUrl(u)
        }
        return runChain(candidates.toList(), data, subtitleCallback, callback, elapsed, "MyCima")
    }

    private suspend fun loadLinksLaRos(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val doc = runCatching {
            app.get(data, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·LaRos", "servers-fail"), callback)
            return true
        }
        val rows = ArrayList<String>()
        val perHost = HashMap<String, Int>()
        for (btn in doc.select("[data-server-url]")) {
            val u = btn.attr("data-server-url").trim()
            if (!u.startsWith("http")) continue
            val host = hostOf(u)
            perHost[host] = (perHost[host] ?: 0) + 1
            rows += u
        }
        // MixDrop-first (one fetch + unpack), then the rest, early stop.
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        val attempted = HashSet<String>()
        var mixTried = 0
        for (u in rows) {
            if (emitted >= 2 || elapsed() > 6000 || mixTried >= 3) break
            if (!hostOf(u).contains("mixdrop")) continue
            mixTried++
            attempted += u
            runCatching { loadExtractor(u, data, subtitleCallback, counting) }
        }
        val remaining = rows.filter { it !in attempted }
        if (emitted < 5) {
            runChain(remaining, data, subtitleCallback, counting, elapsed, "LaRos",
                allowDebug = emitted == 0)
        }
        return true
    }

    /**
     * AUTO ENGLISH SUBTITLES (v7 = v6 + sync-first ranking): CloudStream has no standalone
     * subtitle-plugin type that auto-attaches to other providers'
     * playback, so the tool lives here — inside loadLinks, before
     * any video link is emitted:
     *   Latin title (+ year) → IMDb suggest (key-free) → IMDb id →
     *   OpenSubtitles v3 Stremio endpoint (key-free) → first
     *   lang=eng file → subtitleCallback(SubtitleFile("English", url)).
     * The .srt downloads without auth (verified: The Conjuring 2013,
     * 79 KB / 1087 cues; Breaking Bad S1E1, 8 English files).
     * Everything is fail-soft and fail-fast (8 s caps): if any step
     * misses, playback continues with zero subtitles — never blocked.
     * Titles with no Latin part (fully Arabic titles) cannot be
     * resolved by IMDb suggest and get no subtitle in v4.
     */
    /** v24: per-lane fail-fast timeout for home fetches (10 s).
     * A slow/hung site must return null quickly instead of
     * hanging the whole home. Same HttpURLConnection pattern
     * as httpGetText, but with caller-supplied headers so the
     * HTML lanes keep their browser headers and the MovieBox
     * lane keeps its JSON headers. No coroutines — the real
     * Gradle classpath does not expose them, so plain
     * HttpURLConnection is used instead. */
    private val homeFetchTimeoutMs = 10_000

    private fun httpGetTextHeaders(
        url: String,
        timeoutMs: Int,
        headers: Map<String, String>
    ): String? {
        return runCatching {
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                return@runCatching null
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    private fun httpGetText(url: String, timeoutMs: Int): String? {
        return runCatching {
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", browserUA)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                return@runCatching null
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

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

    private fun resolveImdbId(latinTitle: String, year: Int?): String? {
        val bucket = latinTitle.firstOrNull()?.lowercaseChar() ?: return null
        val q = java.net.URLEncoder.encode(latinTitle, "UTF-8")
        val json = httpGetText(
            "https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json", 8000
        ) ?: return null
        val arr = runCatching {
            JSONObject(json).optJSONArray("d")
        }.getOrNull() ?: return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val kind = o.optString("q")
            if (fallback == null && kind == "feature") fallback = id
            if (kind != "feature" && kind != "TV series") continue
            if (year != null && o.has("y") && o.optInt("y") != year) continue
            return id
        }
        return fallback
    }

    /** Best OpenSubtitles file for one language, sync-first ranking. */
    private fun bestSubFor(arr: JSONArray, lang: String): String? {
        return rankedSubsFor(arr, lang).firstOrNull()
    }

    /** All OpenSubtitles files for one language, best score first. */
    private fun rankedSubsFor(arr: JSONArray, lang: String): List<String> {
        val scored = ArrayList<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("lang") != lang) continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            var score = 0
            if (o.optInt("fpsMilli", 0) == 23976) score += 3
            val fmt = o.optString("releaseFormat")
            if (fmt == "BluRay" || fmt == "WEB") score += 2
            if (o.optString("SubEncoding") == "UTF-8") score += 1
            scored.add(score to u)
        }
        return scored.sortedByDescending { it.first }.map { it.second }
    }

    /** v32: subtitle gear, three modes (HorrorSubs.mode, default
     * dual) — Title (+year) -> IMDb suggest (key-free) ->
     * OpenSubtitles. Sync-first ranking (v7): don't take the
     * first file — a subtitle timed for 25fps on a 23.976fps
     * stream drifts more and more as the film plays. Prefer
     * 23.976fps BluRay/WEB UTF-8 files, which match the
     * standard movie streams best.
     * - "ar": Arabic only (no English fallback — the mode
     *   says Arabic; the MovieBox route may still add its
     *   own Arabic for a MovieBox film).
     * - "en": English only (the old v6 behaviour).
     * - "dual": Arabic + the merged Arabic+English (relay
     *   mergesubs, verified 200+WEBVTT first; local merge
     *   as fallback — same machinery as the MovieBox dual);
     *   Arabic only when no English exists, English only
     *   when no Arabic exists. Playback never waits on
     *   subtitles. */
    private suspend fun emitSubs(
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val mode = HorrorSubs.mode
        return runCatching {
            val year = Regex("""(19|20)\d\d""").find(title)?.value?.toIntOrNull()
            for (candidate in latinTitleCandidates(title)) {
                val tt = resolveImdbId(candidate, year) ?: continue
                val json = httpGetText(
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json",
                    8000
                ) ?: continue
                val arr = runCatching {
                    JSONObject(json).optJSONArray("subtitles")
                }.getOrNull() ?: continue
                val ara = bestSubFor(arr, "ara")
                val eng = bestSubFor(arr, "eng")
                return when (mode) {
                    "ar" -> {
                        if (ara != null) {
                            subtitleCallback(SubtitleFile("العربية", ara))
                            "sub:ar"
                        } else "sub:no-match"
                    }
                    "en" -> {
                        if (eng != null) {
                            subtitleCallback(SubtitleFile("English", eng))
                            "sub:en"
                        } else "sub:no-match"
                    }
                    else -> {
                        if (eng != null && ara != null) {
                            val mergeUrl = "$mbSubsRelayBase/relay/mergesubs?eng=" +
                                java.net.URLEncoder.encode(eng, "UTF-8") +
                                "&ara=" + java.net.URLEncoder.encode(ara, "UTF-8")
                            subtitleCallback(SubtitleFile("العربية", ara))
                            val mergeOk = runCatching {
                                val r = app.get(mergeUrl)
                                r.code in 200..299 && r.text.contains("WEBVTT")
                            }.getOrDefault(false)
                            if (mergeOk) {
                                subtitleCallback(SubtitleFile("العربية + الإنجليزية", mergeUrl))
                                "sub:dual"
                            } else {
                                val stored = mbMergeCaptionsLocal(ara, eng)
                                if (stored != null) {
                                    subtitleCallback(SubtitleFile("العربية + الإنجليزية", stored))
                                    "sub:dual"
                                } else "sub:ar+mf"
                            }
                        } else if (ara != null) {
                            subtitleCallback(SubtitleFile("العربية", ara))
                            "sub:ar-only"
                        } else if (eng != null) {
                            subtitleCallback(SubtitleFile("English", eng))
                            "sub:en-only"
                        } else "sub:no-match"
                    }
                }
            }
            "sub:no-tt"
        }.getOrDefault("sub:err")
    }

    /** Packs title (+year, parsed from the title) into a loadLinks data string. */
    private fun subData(url: String, title: String): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return url + "|||" + enc
    }

    private fun parseSubData(data: String): Triple<String, String, String> {
        val parts = data.split("|||")
        val real = parts.getOrNull(0) ?: data
        val title = parts.getOrNull(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("")
        }.orEmpty()
        return Triple(real, title, "")
    }
    private fun httpPostForm(
        url: String,
        fields: Map<String, String>,
        referer: String,
        timeoutMs: Int = 15000
    ): String? {
        return runCatching {
            val body = fields.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(v, "UTF-8")
            }
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", browserUA)
            conn.setRequestProperty("Referer", asciiSafe(referer))
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded"
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            } ?: return@runCatching null
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    /**
     * TopCinema lane: watch page → li.server--item (data-id,
     * data-server) → POST Single/Server.php (Referer = watch page,
     * XHR) → iframe embed → shared chain. The active server's
     * iframe is also on the watch page itself (server 0).
     * v8: the POST goes to the watch page's own host (see above).
     */
    private suspend fun loadLinksTop(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val watchUrl = data
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·Top", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashSet<String>()
        doc.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("topcinema")) {
                embeds += src
            }
        }
        var postId: String? = null
        val items = ArrayList<Triple<String, String, String>>()
        for (li in doc.select("li.server--item")) {
            val id = li.attr("data-id")
            val idx = li.attr("data-server")
            if (id.isBlank() || idx.isBlank()) continue
            postId = id
            val nm = li.selectFirst("span")?.text()?.trim().orEmpty()
            items += Triple(id, idx, nm)
        }
        // v9 FIX: POST to the watch page's ORIGIN (scheme + host).
        // v8 built postBase as watchUrl minus "/watch/" — that is the
        // FILM URL, so the POST went to
        // https://topcinemaa.vip/<film-slug>/wp-content/.../Server.php
        // and came back HTTP 301 with an EMPTY body — all 10 server
        // POSTs silently skipped, leaving only the single vidtube
        // iframe from the page itself (no stock extractor + CF-403
        // from the sandbox). That is why TopCinema played NOTHING
        // on-device. The POST must go to the site ROOT:
        // https://topcinemaa.vip/wp-content/.../Server.php
        // (verified live 2026-11-08: 10/10 servers return a real
        // iframe: VideoTube, OkStream, UpDown, StreamWish, Doodstream,
        // Filelions, Byse, Firestream, Streamtape, LuluStream).
        // Origin is taken from the fetched page's final URL
        // (doc.baseUri, after redirects) so a future domain move
        // needs no code change.
        val finalWatchUrl = doc.baseUri().ifBlank { watchUrl }
        val postBase = Regex("""^(https?://[^/]+)""").find(finalWatchUrl)
            ?.groupValues?.get(1)
            ?: "https://topcinemaa.vip"
        // v10: POST in resolver-priority order and stop early. v9's
        // sequential POST loop ate the whole wall-clock budget on a
        // slow phone (on-device v9: srv=7 ext=0 dig=0, t=12s — only
        // 6 of 10 POSTs finished and the extractor loop never got a
        // second). Extractor-backed servers first, then packed-JS
        // digs, then the rest; stop once 6 embeds are in hand or the
        // POST stage hits 8 s, each POST capped at 6 s.
        fun postRank(name: String): Int {
            val n = name.lowercase()
            return when {
                n.contains("streamwish") -> 0
                n.contains("doodstream") -> 1
                n.contains("streamtape") -> 2
                n.contains("byse") -> 3
                n.contains("firestream") -> 4
                n.contains("okstream") -> 5
                n.contains("lulustream") -> 6
                else -> 7
            }
        }
        val ordered = items.sortedBy { postRank(it.third) }
        val tPost = System.currentTimeMillis()
        var postTried = 0
        var postOk = 0
        if (postId != null) {
            for ((id, idx, _) in ordered) {
                if (embeds.size >= 6) break
                if (System.currentTimeMillis() - tPost > 8000) break
                postTried++
                val html = httpPostForm(
                    "$postBase/wp-content/themes/movies2023/Ajaxat/Single/Server.php",
                    mapOf("id" to id, "i" to idx),
                    watchUrl,
                    timeoutMs = 6000
                ) ?: continue
                val iframe = Regex("""<iframe[^>]*src="([^"]+)"""")
                    .find(html)?.groupValues?.get(1)
                if (iframe != null && iframe.startsWith("http") &&
                    !iframe.contains("topcinema")
                ) {
                    embeds += iframe
                    postOk++
                }
            }
        }
        val postMs = System.currentTimeMillis() - tPost
        val hosts = embeds.map { hostOf(it) }.joinToString(",").take(80)
        return runChain(
            embeds.toList(), watchUrl, subtitleCallback, callback, elapsed, "Top",
            diagExtra = listOf(
                "post=$postOk/$postTried", "pt=${postMs / 1000}s",
                "hosts=$hosts"
            )
        )
    }

    /**
     * AflamStream lane: post URL's trailing segment is the post id →
     * /watch/?id={id} → .server-btn[data-url] (5 servers) →
     * shared chain.
     */
    private suspend fun loadLinksAf(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val postId = data.trimEnd('/').substringAfterLast('/')
        if (postId.isBlank() || postId == data.trimEnd('/')) {
            emitDebug(listOf("Horror·Af", "id-missing"), callback)
            return true
        }
        val watchUrl = "https://aflamstream.com/watch/?id=$postId"
        val doc = runCatching {
            app.get(watchUrl, headers = browserHeaders).document
        }.getOrNull()
        if (doc == null) {
            emitDebug(listOf("Horror·Af", "watch-fail"), callback)
            return true
        }
        val embeds = LinkedHashSet<String>()
        doc.select(".server-btn[data-url]").forEach {
            val u = it.attr("data-url").trim()
            if (u.startsWith("http")) embeds += u
        }
        doc.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.startsWith("http") && !src.contains("aflamstream")) {
                embeds += src
            }
        }
        return runChain(embeds.toList(), watchUrl, subtitleCallback, callback, elapsed, "Af")
    }

    // ---------------------------------------------------------------
    // MovieBox (v17) — clean-room reimplementation of the
    // MovieBoxProvider v54 (CNC) flow, recovered from its dex
    // (see ~/workspace/moviebox-study/NOTES.md). NO code copied,
    // no .cs3 redistributed. Catalog = web ranking-list (works
    // anonymously); playback = guest JWT token (mobile gateway
    // first, web host fallback) + X-Client-Token + x-tr-signature
    // (HmacMD5 over the canonical string) -> subject/play ->
    // data.streams[]/dash[]/hls[] (signCookie per stream).
    // Captions via get-ext-captions (Arabic pick), fail-soft.
    // KNOWN LIMIT: the sandbox is region-blocked (mobile gateway
    // 403 "Service not available in current region"; play returns
    // empty streams even with a valid token), so playback is NOT
    // verifiable from here — the original v54 WORKS on the user's
    // phone (his word 2026-11-15 21:44), so the same flow is
    // expected to work on-device; the on-device verdict is his.
    // ---------------------------------------------------------------
    private val mbWebBase = "https://moviebox.sg"
    private val mbMobileBase = "https://apig.inmoviebox.com"
    // v19: subtitle relay for the dual Arabic+English merge
    // (Goojara/ReAnime/AnimeGG pattern) — NOT the dead Render
    // relayBase above. mergesubs merges two caption URLs
    // server-side; /relay/store hosts a locally merged VTT.
    private val mbSubsRelayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"
    private val mbRankId = "8614980535946986176"
    private val mbRankCategoryType = "4516404531735022304"
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

    private fun isMovieBox(url: String): Boolean {
        val h = hostOf(url)
        return h == "moviebox.sg" || h.endsWith(".moviebox.sg") ||
            h == "movie-box.co" || h.endsWith(".movie-box.co")
    }

    /** v17 Arabic-only filter: normalize each subtitle language
     * name (strip diacritics/tatweel, أ/إ/آ→ا, ة→ه, ى→ي) and
     * accept the film if ANY name contains "عرب". Arabic shows
     * as "اَلْعَرَبِيَّةُ" (fully vocalized) in the subtitles
     * field — verified live: 33/50 accepted on ranking p1-3. */
    private fun normalizeArabic(s: String): String {
        var t = s.replace(Regex("""[\u064B-\u0652\u0670\u0640]"""), "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
        return t
    }

    private fun hasArabicSub(subtitles: String?): Boolean {
        if (subtitles.isNullOrBlank()) return false
        return subtitles.split(",")
            .any { normalizeArabic(it.trim()).contains("عرب") }
    }

    /** v17: seen detailPaths for the MovieBox section (dedup
     * across sideways-scroll pages; cleared on page 1). */
    private val mbSeenDetailPaths = HashSet<String>()

    private val mbRoman = mapOf(
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
        "XI" to 11, "XII" to 12, "XIII" to 13
    )

    /** v17: franchise base name — strip a trailing "(YYYY)",
     * drop any ": subtitle", drop a trailing "Part/Chapter N",
     * then drop a trailing part number (digits, roman numeral,
     * "3D") — only when something remains. "The Conjuring:
     * Last Rites" -> "The Conjuring"; "Saw X" -> "Saw". */
    private fun mbFranchiseBase(title: String): String {
        var t = title.trim()
        t = Regex("""\(\d{4}\)\s*$""").replace(t, "").trim()
        t = t.substringBefore(":").trim()
        t = Regex(
            """\s+(?:Part|Chapter)\s+([IVXLCDM]+|\d+)\s*$""",
            RegexOption.IGNORE_CASE
        ).replace(t, "").trim()
        val m = Regex("""\s+([IVXLCDM]+|\d+|3D)\s*$""").find(t)
        if (m != null && t.substring(0, m.range.first).trim().length >= 2) {
            t = t.substring(0, m.range.first).trim()
        }
        return Regex("""\s+""").replace(t, " ").trim()
    }

    /** v17: part number from the title tail — "Saw X" -> 10,
     * "Saw II" -> 2, "Final Destination 5" -> 5; 0 when no
     * number is present ("Saw 3D", "The Conjuring: Last
     * Rites" — the year still orders those). */
    private fun mbFranchisePart(title: String): Int {
        var t = title.trim()
        t = Regex("""\(\d{4}\)\s*$""").replace(t, "").trim()
        val m1 = Regex(
            """(?:Part|Chapter)\s+([IVXLCDM]+|\d+)\s*$""",
            RegexOption.IGNORE_CASE
        ).find(t)
        if (m1 != null) {
            val g = m1.groupValues[1].uppercase()
            return mbPartNum(g)
        }
        val m2 = Regex("""\s+([IVXLCDM]+|\d+)\s*$""")
            .find(t.substringBefore(":").trim())
        if (m2 != null) {
            val g = m2.groupValues[1].uppercase()
            return mbPartNum(g)
        }
        return 0
    }

    /** v21: a trailing 4-digit year (1900-2099) is a year, not a
     * part number ("Godzilla 2000" -> part 0, not 2000). */
    private fun mbPartNum(g: String): Int {
        val n = g.toIntOrNull()
        if (n != null && g.length == 4 && n in 1900..2099) return 0
        return mbRoman[g] ?: n ?: 0
    }

    private fun mbFranchiseYear(releaseDate: String?): Int =
        releaseDate?.take(4)?.toIntOrNull() ?: 0

    /** v21: normalized base key for comparison — lowercase,
     * commas/brackets/quotes removed, all whitespace removed.
     * Fixes spacing variants ("The Texas Chain Saw Massacre"
     * == "The Texas Chainsaw Massacre") and bracket variants
     * ("[Rec]" == "REC"). Deliberately does NOT strip a
     * leading "the ": "Conjuring" (the look-alike
     * "Conjuring: The Beyond") must NOT equal "The Conjuring",
     * and "Purge" must NOT equal "The Purge". */
    private fun mbBaseKey(title: String): String {
        var t = mbFranchiseBase(title).lowercase()
        t = t.replace(Regex("""[,\[\]()\"'’]"""), "")
        return Regex("""\s+""").replace(t, "")
    }

    /** v21: normalized title key for dedup — lowercase, bracketed
     * suffixes ("[English]", "[HD]") removed, leading "the "
     * stripped, all whitespace/quotes removed. Unlike mbBaseKey
     * it keeps the subtitle after the colon, so two different
     * films of one franchise in one year ("Godzilla: King of
     * the Monsters" vs "Godzilla: The Planet Eater", 2019) do
     * NOT collide — while spacing variants ("The Texas Chain
     * Saw Massacre") and dub variants ("... [English]") do. */
    private fun mbTitleKey(title: String): String {
        var t = title.lowercase()
        t = Regex("""\[[^\]]*\]""").replace(t, "")
        if (t.trim().startsWith("the ")) t = t.trim().substring(4)
        t = t.replace(Regex("""[\"'’]"""), "")
        return Regex("""\s+""").replace(t, "").trim()
    }

    /** v21: franchise alias groups — renamed sequels that share
     * NO title words with the franchise name, so no structural
     * rule can match them. A base belongs to a group if it
     * equals any member (normalized keys); groups are checked
     * both ways, so opening a renamed sequel still shows the
     * rest. Deliberately NOT included: Ju-on (a separate
     * Japanese series, not The Grudge parts) and the
     * Conjuring-universe crossovers (Annabelle / The Nun are
     * their own franchises, not Conjuring parts). */
    private val mbFranchiseAliases: List<Set<String>> = listOf(
        setOf("saw", "jigsaw", "spiral"),
        setOf("child's play", "bride of chucky", "seed of chucky",
            "curse of chucky", "cult of chucky"),
        setOf("the texas chainsaw massacre", "texas chainsaw massacre",
            "leatherface", "texas chainsaw"),
        setOf("a nightmare on elm street", "nightmare on elm street",
            "new nightmare", "freddy's dead", "freddy vs. jason"),
        setOf("friday the 13th", "freddy vs. jason",
            "jason goes to hell"),
        setOf("the ring", "rings", "ring"),
        setOf("28 days later", "28 weeks later", "28 years later"),
        setOf("godzilla", "shin godzilla", "son of godzilla",
            "the return of godzilla", "return of godzilla")
    ).map { g -> g.map { mbBaseKey(it) }.toSet() }

    /** v21: does a candidate title belong to the current film's
     * franchise? Rules (in order):
     *  A. exact base match (normalized keys).
     *  B. prefix subtitle — the candidate's pre-colon title
     *     starts with the base + a space ("Halloween Kills",
     *     "Annabelle Comes Home", "Godzilla Minus One",
     *     "The Ring Two", "Paranormal Activity - Next of Kin").
     *  C. post-colon franchise — the base of the part after the
     *     colon equals the current base ("Hellbound: Hellraiser
     *     II", "Leatherface: Texas Chainsaw Massacre III").
     *  D. "vs." split — either side's base equals the current
     *     base ("King Kong vs. Godzilla", "Mothra vs. Godzilla").
     *  E. alias group (see mbFranchiseAliases).
     *  F. inverted subtitle — "The <sequence-word> <last word
     *     of the base>" ("The First Purge", "The Forever
     *     Purge"); the middle word must be an ordinal/finality
     *     word so "The Wedding Ring" / "The Christmas Ring" do
     *     NOT match "The Ring".
     * Known trade-off (NOTES.md): Rule B can include a few
     * unrelated same-prefix films ('Halloween Night',
     * 'Insidious Tales') — a title-only match cannot tell a
     * sequel from a same-name film, and missing real parts is
     * the worse failure for his order. The v17 look-alike
     * exclusions still hold: "Conjuring: The Beyond" and
     * "The Nun II" do NOT match "The Conjuring". */
    private fun mbIsFranchisePart(candidate: String, base: String): Boolean {
        val baseKey = mbBaseKey(base)
        if (baseKey.length < 2) return false
        val candBase = mbFranchiseBase(candidate)
        val candKey = mbBaseKey(candidate)
        if (candKey == baseKey) return true // A
        val baseNorm = mbFranchiseBase(base).lowercase()
        // B: prefix subtitle on the pre-colon title
        // ("Halloween Kills", "Annabelle Comes Home", "Godzilla
        // Minus One", "The Ring Two", "Paranormal Activity -
        // Next of Kin"). Punctuation is first normalized to a
        // space, so "Godzilla, Mothra and King Ghidorah: ..."
        // also matches. Gated on a base of 5+ characters so a
        // 3-letter base like "REC" does not swallow "Rec Man".
        val preNorm = candidate.substringBefore(":").trim().lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ").trim()
        if (baseKey.length >= 5 && preNorm.startsWith(baseNorm + " ")) return true
        // C: post-colon franchise — the base of the part after
        // the colon equals the current base AND the post-colon
        // part carries a part number or is longer than the base
        // ("Hellbound: Hellraiser II", "Leatherface: Texas
        // Chainsaw Massacre III"). The number/length guard keeps
        // "Final Girl: Halloween" OUT of "The Ring" (a bare
        // franchise name after the colon is a different film's
        // subtitle, not a part).
        val postColon = candidate.substringAfter(":", "").trim()
        if (postColon.isNotBlank() &&
            mbBaseKey(postColon) == baseKey &&
            (mbFranchisePart(postColon) > 0 ||
                postColon.trim().split(Regex("""\s+""")).size >
                    baseNorm.trim().split(Regex("""\s+""")).size)
        ) return true
        // D: "vs." split.
        val vsParts = Regex("""\s+vs\.?\s+""", RegexOption.IGNORE_CASE)
            .split(candidate)
        if (vsParts.size == 2) {
            for (side in vsParts) {
                if (mbBaseKey(side) == baseKey) return true
                val sideNorm = side.trim().lowercase()
                    .replace(Regex("""[^a-z0-9]+"""), " ").trim()
                if (sideNorm.startsWith(baseNorm + " ")) return true
            }
        }
        // E: alias group.
        for (group in mbFranchiseAliases) {
            if (baseKey in group && candKey in group) return true
        }
        // F: inverted subtitle ("The First Purge").
        val lastWord = baseNorm.substringAfterLast(" ")
        if (lastWord.length >= 4) {
            val candLow = candidate.trim().lowercase()
            if (candLow.startsWith("the ") &&
                candLow.endsWith(" $lastWord")
            ) {
                val middle = candLow.removePrefix("the ")
                    .removeSuffix(" $lastWord").trim()
                if (middle in setOf(
                        "first", "last", "final", "forever",
                        "next", "new", "ultimate"
                    )
                ) return true
            }
        }
        return false
    }

    /** v17: franchise parts for a MovieBox film page (his order
     * 2026-11-15 22:18 — a film with more than one part shows ALL
     * its parts, ordered). v21: the parts search takes the WEB
     * token FIRST (MovieBoxIN v10 lesson — H5 search with the
     * phone's mobile token is suspected to return 400, so the
     * v17 feature may never have worked on the phone; the
     * sandbox always tested it with a web token), falls back to
     * the guest token, and skips the search when no token exists
     * at all (H5 search without a token is 400, proven live).
     * When H5 returns 400/empty, a mobile search/v2 fallback
     * runs (Rennvol pattern — untested live, 403 from the
     * sandbox). Matching is mbIsFranchisePart (rules A–F), NOT
     * exact-base-only. The Arabic-subtitles gate is RELAXED for
     * the parts row ONLY — his order "any film with parts" is
     * unconditional, and the gate demonstrably dropped real
     * parts (Saw XI 2025, Godzilla: Final Wars); the MovieBox
     * lane list stays Arabic-only. Dedup: (normalized title,
     * year) + (base, year, part) only when part > 0 — the old
     * (base,year,part) dedup wrongly dropped a second same-year
     * film with part 0 (Godzilla: The Planet Eater vs King of
     * the Monsters, 2019; Hellraiser: Deader vs Hellworld,
     * 2005). Sorted by year then part number, capped at 50
     * (v17's take(20) dropped the NEWEST parts of long series
     * like Godzilla). The current film is included in its
     * sorted position. Fully fail-soft: any failure returns the
     * current film alone (the caller then shows no
     * recommendations row). */
    private suspend fun mbFranchiseParts(
        title: String,
        detailPath: String,
        releaseDate: String?,
        poster: String?
    ): List<SearchResponse> {
        val displayTitle = title.ifBlank { "MovieBox" }
        val current = movieCard(
            displayTitle,
            "$mbWebBase/ar/moviedetail/$detailPath",
            poster
        )
        val base = mbFranchiseBase(displayTitle)
        if (base.length < 2) return listOfNotNull(current)
        // v21: WEB token first (H5 search), guest token fallback,
        // no token at all -> no doomed call.
        val webToken = runCatching { mbWebToken().first }.getOrNull()
        val guestToken = if (webToken.isNullOrBlank()) {
            runCatching { mbGuestToken().first }.getOrNull()
        } else null
        val token = webToken ?: guestToken
            ?: return listOfNotNull(current)
        val found = ArrayList<Triple<Int, Int, SearchResponse>>()
        val seenTitleYear = HashSet<String>()
        val seenBaseYearPart = HashSet<String>()
        var matched = 0
        var h5Failed = false
        fun consider(o: JSONObject) {
            val t = o.optString("title").ifBlank { o.optString("name") }
            if (t.isBlank()) return
            if (o.optInt("subjectType", 1) != 1) return
            if (!mbIsFranchisePart(t, base)) return
            val dp = o.optString("detailPath")
            if (dp.isBlank()) return
            val y = mbFranchiseYear(o.optString("releaseDate"))
            val p = mbFranchisePart(t)
            // v21: normalized-title dedup (spacing/bracket
            // variants of one listing, e.g. "The Texas Chain Saw
            // Massacre" vs "The Texas Chainsaw Massacre", and
            // "[English]" dub variants) + (base, year, part)
            // only when part > 0 (catches "Saw 11" vs "Saw XI"
            // without dropping a second same-year film).
            if (!seenTitleYear.add(mbTitleKey(t) + "|" + y)) return
            if (p > 0 &&
                !seenBaseYearPart.add(mbBaseKey(base) + "|" + y + "|" + p)
            ) return
            val coverUrl = o.optJSONObject("cover")?.optString("url")
                ?.ifBlank { null }
                ?: o.optString("poster").ifBlank { null }
                ?: o.optString("coverUrl").ifBlank { null }
            val card = movieCard(
                t,
                "$mbWebBase/ar/moviedetail/$dp",
                coverUrl
            ) ?: return
            matched++
            found += Triple(if (y > 0) y else 9999, p, card)
        }
        // The search walk mirrors the verified live behaviour:
        // pages return 6-16 items each and hasMore turns false
        // around page 6 even while totalCount reads higher — the
        // API's reachable window, not our cap, ends the walk.
        // v21: perPage 50 (proven live: 13 items/page vs ~6 at
        // perPage 20 for "Final Destination").
        var page = 1
        while (page <= 6) {
            val body = JSONObject().apply {
                put("keyword", base)
                put("page", page)
                put("perPage", 50)
                put("subjectType", 1)
            }.toString()
            val res = runCatching {
                app.post(
                    "$mbWebBase/wefeed-h5api-bff/subject/search",
                    headers = mapOf(
                        "User-Agent" to browserUA,
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $token",
                        "X-MB-Token" to token
                    ),
                    requestBody = body.toRequestBody(
                        "application/json".toMediaType()
                    )
                )
            }.getOrNull()
            if (res == null) {
                h5Failed = true
                break
            }
            if (res.code !in 200..299) {
                h5Failed = true
                break
            }
            val data = runCatching {
                JSONObject(res.text).optJSONObject("data")
            }.getOrNull()
            if (data == null) {
                h5Failed = true
                break
            }
            val items = data.optJSONArray("items")
            if (items == null || items.length() == 0) {
                if (page == 1) h5Failed = true
                break
            }
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { consider(it) }
            }
            val pager = data.optJSONObject("pager")
            if (pager != null && pager.has("hasMore") &&
                !pager.optBoolean("hasMore")
            ) break
            page++
        }
        // v21: mobile search/v2 fallback — only when H5 failed
        // or matched nothing.
        if (h5Failed || matched == 0) {
            runCatching { mbFranchisePartsMobile(base, token, ::consider) }
        }
        if (current != null) {
            val curYear = mbFranchiseYear(releaseDate)
            val curPart = mbFranchisePart(displayTitle)
            val k1 = mbTitleKey(displayTitle) + "|" + curYear
            val k2 = mbBaseKey(base) + "|" + curYear + "|" + curPart
            if (seenTitleYear.add(k1) &&
                (curPart <= 0 || seenBaseYearPart.add(k2))
            ) {
                found += Triple(
                    if (curYear > 0) curYear else 9999, curPart, current
                )
            }
        }
        found.sortWith(
            compareBy<Triple<Int, Int, SearchResponse>> { it.first }
                .thenBy { it.second }
                .thenBy { it.third.name }
        )
        return found.map { it.third }.take(50)
    }

    /** v21: mobile search/v2 fallback for the parts search
     * (Rennvol pattern — POST subject-api/search/v2, signed
     * headers with body, Bearer guest token). Response arrays
     * are merged tolerantly (first non-empty of items /
     * subjects / subjectList / list / results[].subjects);
     * cards need a non-blank detailPath (this module's load()
     * cannot open a subjectId-only card). Pages 1..2 only.
     * UNTESTED LIVE: the mobile gateway is 403 from the
     * sandbox, so this path is built from Rennvol's published
     * code and MovieBoxIN's proven mobile branch — the phone
     * is the judge. */
    private suspend fun mbFranchisePartsMobile(
        keyword: String,
        token: String,
        consider: (JSONObject) -> Unit
    ) {
        var page = 1
        while (page <= 2) {
            val body = JSONObject().apply {
                put("page", page)
                put("perPage", 20)
                put("keyword", keyword)
            }.toString()
            val url = "$mbMobileBase/wefeed-mobile-bff/subject-api/search/v2"
            val headers = mbSignedHeaders(
                "POST", url, mbUaToken, mbClientInfoToken(),
                body = body, contentType = "application/json; charset=utf-8"
            ).toMutableMap()
            headers["Authorization"] = "Bearer $token"
            headers["X-MB-Token"] = token
            val res = runCatching {
                app.post(
                    url, headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
            }.getOrNull() ?: break
            if (res.code !in 200..299) break
            val data = runCatching {
                JSONObject(res.text).optJSONObject("data")
            }.getOrNull() ?: break
            val arr = mbFranchiseMobileArray(data) ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { consider(it) }
            }
            val pager = data.optJSONObject("pager")
            if (pager != null && pager.has("hasMore") &&
                !pager.optBoolean("hasMore")
            ) break
            page++
        }
    }

    /** v21: first non-empty parseable subject array in a mobile
     * search/v2 response (Rennvol's subjectsOf order). */
    private fun mbFranchiseMobileArray(data: JSONObject): JSONArray? {
        for (key in listOf("items", "subjects", "subjectList", "list")) {
            val arr = data.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        val results = data.optJSONArray("results")
        if (results != null) {
            for (i in 0 until results.length()) {
                val arr = results.optJSONObject(i)?.optJSONArray("subjects")
                if (arr != null && arr.length() > 0) return arr
            }
        }
        return null
    }

    /** loadLinks data for MovieBox: url ||| title ||| subjectId. */
    private fun movieBoxData(url: String, title: String, subjectId: String): String =
        subData(url, title) + "|||" + subjectId

    // --- v54 auth (protocol constants recovered from the dex) ---
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
     * empty strings. */
    private fun mbCanonical(
        method: String,
        url: String,
        ts: Long,
        body: String? = null,
        contentType: String = "application/json"
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
        // v21: body-aware canonical (Rennvol pattern — the
        // canonical is [method, accept, contentType, bodyLength,
        // ts, bodyHash, url]; a null body keeps the old empty
        // slots, so every existing GET caller signs exactly as
        // before. A POST must pass its body so the gateway's
        // signature check matches.
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyLength = bodyBytes?.size?.toString() ?: ""
        val bodyHash = if (bodyBytes != null) {
            md5Hex(bodyBytes.copyOfRange(0, minOf(bodyBytes.size, 102400)))
        } else ""
        return listOf(
            method.uppercase(), "application/json", contentType,
            bodyLength, ts.toString(), bodyHash, curl
        ).joinToString("\n")
    }

    /** X-Client-Token = "<ts>,<md5(reverse(ts))>"; x-tr-signature
     * = "<ts>|2|<base64(HmacMD5(key, canonical))>". v21: optional
     * body + contentType for POST signing (mobile search/v2);
     * GET callers pass neither and sign exactly as before. */
    private fun mbSignedHeaders(
        method: String,
        url: String,
        ua: String,
        clientInfo: String,
        body: String? = null,
        contentType: String = "application/json"
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val xct = "$ts,${md5Hex(ts.toString().reversed().toByteArray(Charsets.UTF_8))}"
        val canonical = mbCanonical(method, url, ts, body, contentType)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(mbKey(mbKeyDefaultB64), "HmacMD5"))
        val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sig = "$ts|2|" + Base64.encodeToString(sigBytes, Base64.NO_WRAP)
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json",
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
     * gateway first (v54 order), web host fallback — the web host
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
                "?id=$mbRankId&page=1&perPage=1"
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

    @Volatile
    private var mbCachedWebToken: String? = null
    @Volatile
    private var mbCachedWebAt: Long = 0

    /** v21: WEB token, minted from the web host ONLY (mirror of
     * MovieBoxIN v10's mbWebToken — same ranking-list/content
     * call, separate 1 h cache). WHY: H5 subject/search is
     * token-gated and the phone's guest token is a MOBILE token
     * (mbGuestToken mints mobile-first); the sandbox always
     * tested H5 search with a web token while the phone always
     * used a mobile token, and phone search never worked in any
     * version until v10 switched the H5 branch to web-first
     * («البحث تحسن»). The parts search takes this token FIRST;
     * a mobile token against H5 search is suspected to return
     * 400 «invalid token» (unprovable from the sandbox — the
     * mobile mint is 403 here; the phone is the judge). */
    private suspend fun mbWebToken(): Pair<String?, String> {
        val now = System.currentTimeMillis()
        val cached = mbCachedWebToken
        if (cached != null && now - mbCachedWebAt < 3600_000) {
            return cached to "webcached"
        }
        val webUrl = "$mbWebBase/wefeed-h5api-bff/ranking-list/content" +
            "?id=$mbRankId&page=1&perPage=1"
        val tok = mbTokenFrom(
            webUrl, signed = false, clientInfo = null, ua = browserUA
        )
        if (tok != null) {
            mbCachedWebToken = tok
            mbCachedWebAt = now
        }
        return tok to "web"
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

    /** v17: CNC-faithful emit (MovieBoxProviderIN loadLinks$
     * emitStream$2, lines 780-794 — byte-identical logic in v54's
     * emitNetStream$2). VIDEO links get Origin + the full
     * player-page Referer + the mobile Chrome UA + the signed
     * cookie under the stream's own signHeaderKey (default
     * "X-MB-Token"); every other type gets {Referer:
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
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val playerReferer = "$playBase/movies/$detailPath?id=$subjectId" +
            "&type=/movie/detail&detailSe=&detailEp=&lang=en"
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

    /** v18: is this caption object an Arabic track? The caption
     * object fields (proven from the CNC dex + the CloudX model)
     * are lan (code, e.g. "ar"), lanName (display name, e.g.
     * "Arabic"), url — there is NO "language" field, which v17
     * checked (so v17's `language == "ar"` never matched), and
     * lanName is Latin ("Arabic"), so the "عرب" check on it
     * never matched either. Accept every plausible form. */
    private fun mbIsArabicCaption(o: JSONObject): Boolean {
        val lan = o.optString("lan").lowercase()
        val lang = o.optString("language").lowercase()
        if (lan == "ar" || lan == "ara" || lang == "ar" || lang == "ara") return true
        val lanName = o.optString("lanName")
        if (normalizeArabic(lanName).contains("عرب")) return true
        if (lanName.lowercase().contains("arab")) return true
        return false
    }

    /** v19: mirror of mbIsArabicCaption for English tracks. */
    private fun mbIsEnglishCaption(o: JSONObject): Boolean {
        val lan = o.optString("lan").lowercase()
        val lang = o.optString("language").lowercase()
        if (lan == "en" || lan == "eng" || lang == "en" || lang == "eng") return true
        val lanName = o.optString("lanName")
        if (lanName.lowercase().contains("english")) return true
        if (normalizeArabic(lanName).contains("انجليز")) return true
        return false
    }

    /** v19: scan one captions array for the FIRST Arabic and the
     * FIRST English track. Returns (araUrl, engUrl) — either
     * may be null. Both come from the SAME array (the same
     * caption call that returns Arabic returns English too). */
    private fun mbPickCaptions(
        arr: org.json.JSONArray?
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

    // ---- v19 local subtitle merge (fallback when the relay
    // mergesubs URL does not serve) ----
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

    /** Emit subtitles from one captions array. Returns the sub
     * diagnostic: "ar+en" when the dual option was emitted,
     * "ar" / "en" for a single side, "ar+mf" when both sides
     * exist but no dual file could be built (merge + local
     * fallback both failed — the Arabic single is still
     * emitted), or null when the array holds neither.
     * Dual = the relay's /relay/mergesubs (Goojara v20 pattern:
     * it fetches both files and returns one merged VTT),
     * verified with one GET before advertising it; the local
     * merge + store above is the fallback. */
    private suspend fun mbEmitFromArray(
        arr: org.json.JSONArray?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        val (ara, eng) = mbPickCaptions(arr)
        if (ara == null && eng == null) return null
        // v32: the subtitle gear mode gates MovieBox captions
        // too — "ar" emits Arabic only, "en" English only,
        // "dual" (default) keeps the v19 behaviour below.
        when (HorrorSubs.mode) {
            "ar" -> {
                if (ara != null) {
                    subtitleCallback(SubtitleFile("العربية", ara))
                    return "ar"
                }
                return null
            }
            "en" -> {
                if (eng != null) {
                    subtitleCallback(SubtitleFile("English", eng))
                    return "en"
                }
                return null
            }
        }
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
                subtitleCallback(SubtitleFile("العربية + الإنجليزية", mergeUrl))
                return "ar+en"
            }
            val stored = mbMergeCaptionsLocal(ara, eng)
            if (stored != null) {
                subtitleCallback(SubtitleFile("العربية + الإنجليزية", stored))
                return "ar+en"
            }
            return "ar+mf"
        }
        if (ara != null) return "ar"
        subtitleCallback(SubtitleFile("English", eng!!))
        return "en"
    }

    /** Captions array from a caption-endpoint body: the mobile
     * shape (data.extCaptions) or the H5 shape (data.captions),
     * plus top-level fallbacks. */
    private fun mbCaptionsArray(
        json: JSONObject?,
        vararg keys: String
    ): org.json.JSONArray? {
        if (json == null) return null
        val data = json.optJSONObject("data")
        for (k in keys) {
            data?.optJSONArray(k)?.let { return it }
            json.optJSONArray(k)?.let { return it }
        }
        return null
    }

    /** Captions for a film (v19: Arabic + English dual).
     * Returns a diagnostic: "ar+en" when the dual option was
     * emitted, "ar" / "en" for a single side, "ar+mf" when both
     * sides exist but no dual file could be built, else a
     * compact "none:<route>=<code>/..." trail (e.g.
     * "none:h5=200-empty/mob=403").
     * v18: the v17 routes called ONLY the mobile gateway
     * (apig.inmoviebox.com) — 403 "Service not available in
     * current region" from every US IP tested (and the web BFF
     * caption endpoint 403s "invalid region" the same way), so
     * on the user's US network the caption call died silently
     * while playback (H5 bases) worked. The H5-api-bff caption
     * endpoint (moviebox.sg/wefeed-h5api-bff/subject/caption)
     * answers HTTP 200 from a US IP — it is NOT region-gated —
     * so it goes FIRST here, with the REAL stream id + format
     * from the play response (id=subjectId alone returns an
     * empty captions array; a missing id is a 400 "empty id").
     * The mobile routes stay as fallback. The CNC dex emits
     * SubtitleFile with (lang, url) and NO headers, so no
     * headers are passed with the caption URL. */
    private suspend fun mbEmitArabicCaption(
        subjectId: String,
        detailPath: String,
        playBase: String,
        token: String,
        h5Caps: List<Pair<String, String>>,
        streamId: String?,
        resourceId: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String {
        val trail = ArrayList<String>()
        // Route H5 first: NOT region-gated (200 from a US IP).
        for ((sid, fmt) in h5Caps) {
            val capUrl = "$playBase/wefeed-h5api-bff/subject/caption" +
                "?format=$fmt&id=$sid&subjectId=$subjectId" +
                "&detailPath=$detailPath"
            val headers = mbSignedHeaders(
                "GET", capUrl, mbUaToken, mbClientInfoToken()
            ) + mapOf(
                "Authorization" to "Bearer $token",
                "X-MB-Token" to token
            )
            val res = runCatching { app.get(capUrl, headers = headers) }.getOrNull()
            val code = res?.code ?: 0
            if (res == null || code !in 200..299) {
                trail += "h5=$code"
                continue
            }
            val json = runCatching { JSONObject(res.text) }.getOrNull()
            val arr = mbCaptionsArray(json, "captions", "extCaptions")
            if (arr == null || arr.length() == 0) {
                trail += "h5=$code-empty"
                continue
            }
            mbEmitFromArray(arr, subtitleCallback)?.let { return it }
            trail += "h5=$code-nocap"
        }
        // Route mobile ext-captions (subjectId only).
        runCatching {
            val extUrl = "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                "get-ext-captions?subjectId=$subjectId"
            val extHeaders = mbSignedHeaders(
                "GET", extUrl, mbUaToken, mbClientInfoToken()
            ) + mapOf(
                "Authorization" to "Bearer $token",
                "X-MB-Token" to token
            )
            val extRes = app.get(extUrl, headers = extHeaders)
            trail += "mob=${extRes.code}"
            if (extRes.code in 200..299) {
                val extJson = runCatching { JSONObject(extRes.text) }.getOrNull()
                val arr = mbCaptionsArray(extJson, "extCaptions", "captions")
                mbEmitFromArray(arr, subtitleCallback)?.let { return it }
            }
        }
        // Route mobile stream-captions (needs stream + resource ids).
        if (!streamId.isNullOrBlank() && !resourceId.isNullOrBlank()) {
            runCatching {
                val streamUrl = "$mbMobileBase/wefeed-mobile-bff/subject-api/" +
                    "get-stream-captions?subjectId=$subjectId" +
                    "&streamId=$streamId&episode=0&resourceId=$resourceId"
                val streamHeaders = mbSignedHeaders(
                    "GET", streamUrl, mbUaToken, mbClientInfoToken()
                ) + mapOf(
                    "Authorization" to "Bearer $token",
                    "X-MB-Token" to token
                )
                val streamRes = app.get(streamUrl, headers = streamHeaders)
                trail += "mobstr=${streamRes.code}"
                if (streamRes.code in 200..299) {
                    val streamJson = runCatching { JSONObject(streamRes.text) }.getOrNull()
                    val arr = mbCaptionsArray(streamJson, "captions", "extCaptions")
                    mbEmitFromArray(arr, subtitleCallback)?.let { return it }
                }
            }
        }
        return "none:" + trail.joinToString("/")
    }

    private suspend fun loadLinksMovieBox(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        elapsed: () -> Long
    ): Boolean {
        val parts = data.split("|||")
        val realData = parts.getOrNull(0) ?: data
        val subjectId = parts.getOrNull(2).orEmpty()
        val detailPath = realData.substringAfter("/moviedetail/")
            .substringBefore("?").substringBefore("#").trim()
        var streamsFound = 0
        var emitted = 0
        var playCode = "?"
        var tokVia = "none"
        var streamId: String? = null
        var resourceId: String? = null
        var subDiag = "?"
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        if (subjectId.isBlank() || detailPath.isBlank()) {
            emitDebug(
                listOf(
                    "Horror v21·MovieBox", "srv=0", "ext=0", "dig=0",
                    "relay=0", "t=${elapsed() / 1000}s", "tok=$tokVia",
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
                // v17 (IN dex, loadLinks$1 ~110f-1133; v54
                // identical): the play Referer is the FULL
                // player-page URL on the SAME base, with the
                // detailPath in the path — NOT the relative
                // "/movies/?id=..." v16 sent. detailPath goes
                // into the play URL RAW (no URL-encoding).
                val referer = "$base/movies/$detailPath?id=$subjectId" +
                    "&type=/movie/detail&detailSe=&detailEp=&lang=en"
                val playUrl = "$base/wefeed-h5api-bff/subject/play" +
                    "?subjectId=$subjectId&se=0&ep=0" +
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
                    streamId = streamObjs[0].optString("id")
                        .ifBlank { null } ?: streamId
                    if (resourceId == null) {
                        resourceId = streamObjs[0].optString("resourceId")
                            .ifBlank { null }
                    }
                }
                // v18: H5 caption candidates — (stream id, format)
                // from the play response, first id per format.
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
                    if (h5Caps.size >= 3) break
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
                        base, detailPath, subjectId, counting
                    )
                }
                if (emitted > 0) {
                    subDiag = mbEmitArabicCaption(
                        subjectId, detailPath, base, token, h5Caps,
                        streamId, resourceId, subtitleCallback
                    )
                    break
                }
            }
        }
        if (emitted == 0 || subDiag != "ar+en") {
            // v18: a missing subtitle must never be silent again —
            // emit the DEBUG line even when playback links exist.
            emitDebug(
                listOf(
                    "Horror v21·MovieBox", "srv=$streamsFound",
                    "ext=$emitted", "dig=0", "relay=0",
                    "t=${elapsed() / 1000}s", "tok=$tokVia",
                    "play=$playCode", "sub=$subDiag"
                ),
                callback
            )
        }
        return true
    }
}
