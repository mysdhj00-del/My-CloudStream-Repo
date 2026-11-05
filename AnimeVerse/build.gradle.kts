// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v18: Miruro.tv (ALL 5 sections) + GogoAnime gogoanime.fi (ALL 4
// sections: latest episodes, recommendations, top, full catalog),
// each titled "<section> — <Site>" with full sideways contents
// (standing rule 2026-11-05). Anilab2 joins as a new site file
// once its playback chain verifies; see the site registry in
// AnimeVerseProvider.kt.
version = 18

android {
    namespace = "com.animeverse"
}

cloudstream {
    description = "AnimeVerse - إضافة أنمي متعددة المواقع: Miruro.tv + GogoAnime (مواقع أخرى قريباً)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
