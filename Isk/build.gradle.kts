// 3isk (Qessat Ashq) CloudStream plugin module.
version = 2

android {
    namespace = "com.isk"
}

cloudstream {
    description = "3isk Qessat Ashq - Turkish series dubbed/subtitled and movies"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("TvSeries", "Movie", "Anime")
    language = "ar"
}
