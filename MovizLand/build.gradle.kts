// MovizLand CloudStream plugin module.
version = 2

android {
    namespace = "com.movizland"
}

cloudstream {
    description = "MovizLand (موفيز لاند) - Arabic portal: foreign/Arabic movies, Arabic + foreign + Turkish (subbed/dubbed) series, anime"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
