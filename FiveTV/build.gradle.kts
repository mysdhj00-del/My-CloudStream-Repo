// FiveTV CloudStream plugin module.
version = 6

android {
    namespace = "com.fivetv"
}

cloudstream {
    description = "FiveTV (فايف تي في) - أفلام ومسلسلات مترجمة من 5TV: أفلام أجنبية، الدراما اليابانية، الأفلام الصينية، الأفلام الكورية، الدراما الكورية، وأحدث الحلقات والإضافات والأفلام، مع بحث كامل"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
