// Qesset CloudStream plugin module.
version = 2

android {
    namespace = "com.qesset"
}

cloudstream {
    description = "Qesset (قصة عشق) - Turkish series and movies subtitled in Arabic (qesset.net) + dubbed series (k.3chk.net)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
