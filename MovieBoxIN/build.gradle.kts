// MovieBoxIN CloudStream plugin module.
version = 2

android {
    namespace = "com.movieboxin"
}

cloudstream {
    description = "MovieBoxIN — movies and series from MovieBox (India catalog), with English, Arabic and dual English+Arabic subtitles from MovieBox itself"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
