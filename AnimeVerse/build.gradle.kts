// AnimeVerse CloudStream plugin module — multi-site anime addon.
// v14: Miruro.tv + AnimoHub (Dooo panel, chain traced live
// 2026-11-05; v14 adds the audio-language hint to AnimoHub
// movie source names). Anilab2 joins as a new site file once
// its playback chain verifies; see the site registry in
// AnimeVerseProvider.kt.
version = 14

android {
    namespace = "com.animeverse"
}

cloudstream {
    description = "AnimeVerse - إضافة أنمي متعددة المواقع: Miruro.tv + AnimoHub"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
}
