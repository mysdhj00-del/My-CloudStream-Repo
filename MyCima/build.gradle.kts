// MyCima CloudStream plugin module.
version = 3

android {
    namespace = "com.mycima"
}

cloudstream {
    description = "MyCima (Cimawbas) - Arabic dubbed/subtitled movies, series and anime"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "ar"
}
