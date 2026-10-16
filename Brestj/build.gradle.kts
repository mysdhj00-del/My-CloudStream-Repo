// Brestj CloudStream plugin module.
version = 2

android {
    namespace = "com.brestj"
}

cloudstream {
    description = "Brestj - Arabic dubbed/subtitled movies, series and anime"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
