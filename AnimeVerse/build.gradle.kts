// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v17: Miruro.tv only — ALL 5 sections (trending, latest
// episodes, top airing, upcoming, schedule), each titled
// "<section> — Miruro" with full sideways contents (standing
// rule 2026-11-05). Anilab2 joins as a new site file once
// its playback chain verifies; see the site registry in
// AnimeVerseProvider.kt.
version = 17

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
