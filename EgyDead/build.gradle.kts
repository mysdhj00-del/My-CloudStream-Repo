// EgyDead CloudStream plugin module.
version = 12


android {
    namespace = "com.egydead"
}


cloudstream {
    description = "EgyDead - Arabic dubbed/subtitled movies, series and anime"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
