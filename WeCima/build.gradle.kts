// WeCima CloudStream plugin module.
version = 13


android {
    namespace = "com.wecima"
}


cloudstream {
    description = "WeCima - Arabic dubbed/subtitled movies, series and anime"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
