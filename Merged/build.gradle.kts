// Merged CloudStream plugin module.
version = 6

android {
    namespace = "com.merged"
}

cloudstream {
    description = "Merged (مدمج) - Movies + anime from foreign sites, direct streams, with automatic subtitles (English / Arabic / both)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
