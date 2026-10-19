// LaRosTV CloudStream plugin module.
version = 1

android {
    namespace = "com.larozatv"
}

cloudstream {
    description = "LaRosTV - Arabic dubbed/subtitled movies, series and anime (larozatv.com)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
