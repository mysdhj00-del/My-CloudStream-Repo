// Cima4y CloudStream plugin module.
version = 4

android {
    namespace = "com.cima4y"
}

cloudstream {
    description = "Cima4y - cima4y.net: Arabic movies, series and anime (سيما فور يو)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
