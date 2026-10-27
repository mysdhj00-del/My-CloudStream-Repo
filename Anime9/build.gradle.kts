// Anime9 CloudStream plugin module (standalone 9anime extension).
version = 1

android {
    namespace = "com.anime9"
}

cloudstream {
    description = "9anime (أنمي 9) - Anime direct streams with automatic subtitles (English / Arabic / both, plus machine-translated Arabic when no Arabic file exists)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("TvSeries")
    language = "ar"
}
