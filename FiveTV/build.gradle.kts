// FiveTV CloudStream plugin module.
version = 1

android {
    namespace = "com.fivetv"
}

cloudstream {
    description = "FiveTV (فايف تي في) - أفلام ومسلسلات مترجمة من 5TV: أحدث الحلقات والإضافات والأفلام، مع بحث كامل"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
