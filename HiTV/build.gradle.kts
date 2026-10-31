// HiTV CloudStream plugin module (Super drama world HiTV, v3.13.3 app lineage).
// v2: detail URLs are absolute (CloudStream normalises a custom scheme
// against mainUrl, which broke v1 detail pages on-device).
// v3: DEBUG test source removed at the user's order (2026-10-31 21:13
// EET) — loadLinks now emits only the real HiTV link.
version = 3

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
