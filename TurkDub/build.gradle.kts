// TurkDub CloudStream plugin module.
version = 2

android {
    namespace = "com.turkdub"
}

cloudstream {
    description = "TurkDub (تركي مدبلج) - Turkish series DUBBED in Arabic, aggregated from Fosta, Qesset-Dub (k.3chk.net) and Brstej"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("TvSeries")
    language = "ar"
}
