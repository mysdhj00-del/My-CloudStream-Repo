// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v1: Miruro.tv only (full chain traced live 2026-11-05). Anilab2 and
// AnimoHub join as new site files once their playback chains verify;
// see the site registry in AnimeVerseProvider.kt.
version = 5

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
