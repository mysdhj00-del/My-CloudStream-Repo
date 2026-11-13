// ReAnime CloudStream plugin module.
// Copied from raghav ReAnime v9 (github.com/KSHITIJ8473/raghav), rewritten:
// no coroutines, no Jackson, no Firebase, no donation prompt, Arabic UI,
// plus an EN->AR subtitle gear (phone-side free Google Translate) for
// every English track the flixcloud embed offers.
version = 5

android {
    namespace = "com.reanime"
}

cloudstream {
    description = "Re:ANIME - أنمي مترجم ومدبلج مع ترجمة عربية تلقائية لكل مسار إنجليزي (ترجمة Google المجانية)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "ar"
}
