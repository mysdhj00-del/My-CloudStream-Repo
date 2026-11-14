// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v22: settings gear (openSettings) — v21 shipped WITHOUT one,
// so the repo list showed no gear next to AnimeVerse. Same three
// subtitle-mode options as Goojara; emissions in all three sites
// gated by the mode (default "dual" = v21 behaviour); DEBUG gains
// mode:<ar|en|dual>.
version = 22

android {
    namespace = "com.animeverse"
}

cloudstream {
    description = "AnimeVerse - إضافة أنمي متعددة المواقع: Miruro.tv + GogoAnime + Aniwatch (مواقع أخرى قريباً)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
