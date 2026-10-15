// Viu CloudStream plugin module.
version = 1

android {
    namespace = "com.viu"
}

cloudstream {
    description = "Viu Egypt - series and movies (guest/free catalog, needs EG network or VPN)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 3

    tvTypes = listOf("TvSeries", "Movie", "Anime")
    language = "ar"
}
