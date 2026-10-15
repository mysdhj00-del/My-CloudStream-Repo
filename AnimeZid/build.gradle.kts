// AnimeZid CloudStream plugin module.
version = 1

android {
    namespace = "com.animezid"
}

cloudstream {
    description = "AnimeZid - Arabic dubbed/subtitled anime, cartoons and movies"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "Movie")
    language = "ar"
}
