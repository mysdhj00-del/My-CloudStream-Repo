// Goojara CloudStream plugin module.
version = 18

android {
    namespace = "com.goojara"
}

cloudstream {
    description = "Goojara - أفلام ومسلسلات أجنبية من Goojara مع ترس ترجمة بثلاثة خيارات (عربي، إنجليزي، عربي + إنجليزي)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
