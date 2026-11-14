// AnimeGG CloudStream plugin module.
// animegg.org — anime subbed & dubbed, Desi chain (AniList GraphQL
// catalog + iframe/embed media regex + loadExtractor fallback),
// plus the EN->AR subtitle gear (OpenSubtitles + phone-side free
// Google Translate with MyMemory fallback) and trailer v3.
version = 2

android {
    namespace = "com.animegg"
}

cloudstream {
    description = "AnimeGG - أنمي مترجم ومدبلج مع ترجمة عربية تلقائية لكل حلقة (ترجمة AI مجانية)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "ar"
}
