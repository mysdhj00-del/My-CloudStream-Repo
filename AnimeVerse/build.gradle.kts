// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v21: Goojara v26 translation engine ported in as
// AnimeTranslate (gtx + MyMemory fallback, cached) — replaces
// the gtx-only translate in all three sites (Miruro, GogoAnime,
// Aniwatch); DEBUG tags v21 on every member.
version = 21

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
