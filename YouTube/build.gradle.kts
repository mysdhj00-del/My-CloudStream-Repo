// YouTube CloudStream plugin module.
version = 6

android {
    namespace = "com.ytube"
}

cloudstream {
    description = "YouTube - channels playlists, published playlists, music search; playback and downloads via the app extractor; optional account sign-in (cookies) for your own playlists"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
