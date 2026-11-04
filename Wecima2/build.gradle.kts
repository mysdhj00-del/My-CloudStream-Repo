// Wecima2 CloudStream plugin module.
version = 4

android {
    namespace = "com.wecima2"
}

cloudstream {
    description = "Wecima2 - wecima.cx: Arabic dubbed/subtitled movies, series and anime (WecimaEngine)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
