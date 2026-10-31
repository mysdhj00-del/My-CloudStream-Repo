// HiTV CloudStream plugin module (Super drama world HiTV, v3.13.3 app lineage).
// v2: detail URLs are absolute (CloudStream normalises a custom scheme
// against mainUrl, which broke v1 detail pages on-device).
version = 2

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
