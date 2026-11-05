// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v16: Miruro.tv only — AnimoHub removed at the user's order
// 2026-11-05 ("مصدر ضعيف"). Anilab2 joins as a new site file
// once its playback chain verifies; see the site registry in
// AnimeVerseProvider.kt.
version = 16

android {
    namespace = "com.animeverse"
}

cloudstream {
    description = "AnimeVerse - إضافة أنمي متعددة المواقع: Miruro.tv (مواقع أخرى قريباً)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
