// HorrorMovies CloudStream plugin module.
version = 3

android {
    namespace = "com.horror"
}

cloudstream {
    description = "HorrorMovies (أفلام رعب) - Horror movies aggregated from MovizLand, EgyBest, MyCima and LaRosTV, with ratings where the source provides them"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie")
    language = "ar"
}
