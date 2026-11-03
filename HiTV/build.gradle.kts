// HiTV CloudStream plugin module (Super drama world HiTV, v3.13.3 app lineage).
// v2: detail URLs are absolute (CloudStream normalises a custom scheme
// against mainUrl, which broke v1 detail pages on-device).
// v3: DEBUG test source removed at the user's order (2026-10-31 21:13
// EET) — loadLinks now emits only the real HiTV link.
// v4: request 1080p explicitly via definition=GROOT_HD on the ios_h5
// play endpoint (verified live 2026-11-01 on 3 titles), with a fallback
// to the default FD stream when HD is not returned.
// v5: search fallback — the server search index returns empty for every
// keyword (verified 2026-11-01), so search() scans the home sections
// and matches title / videoName / English slug. Published after v4 to
// avoid two competing publishes.
// v6: dual subtitles — when a title carries both English and Arabic
// subtitle files, loadLinks also emits one merged "English + العربية"
// track (English line above Arabic in the same cue) via the existing
// relay /relay/mergesubs route (verified live 2026-11-01 on Colony).
// v7: stronger search — server attempt first, then a full home scan
// (all navs, pages 1-3, no early exit, capped at 50) matching title,
// videoName, English slug, plot introduction and tags.
// v8: server search retried on failure only; home scan over dynamic navs.
// v9: known-ID search fallback (TEST) — verified shared titles offered
// directly when normal search finds nothing (IRIS 2009, id 15164).
version = 9

android {
    namespace = "com.hitv"
}

cloudstream {
    description = "HiTV (Super drama world) - Asian dramas and movies, full-length streams via the app's own API (browse from home sections; search is broken on this service version)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
