// Miruro CloudStream plugin module.
version = 1

android {
    namespace = "com.miruro"
}

cloudstream {
    description = "Miruro - Anime from miruro.cx catalog API (all sections), direct streams"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
