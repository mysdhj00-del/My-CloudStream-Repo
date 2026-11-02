// TurkMood CloudStream plugin module (TurkMood app, com.esheaq.app v1.0.13).
// v1: built against the app's OPEN WordPress REST API, verified live 2026-11-02:
//   base https://turkmoodapi.xyz/wp-json/faselhd/v1/ (no login, no tokens)
//   - catalog: /series, /movies (+ category variants), /episodes/latest,
//     all paginated (page/per_page, default 20)
//   - search: /search?q= (full catalog, series + movies) and
//     /advanced-search (type/genre/year/country filters)
//   - detail: /series/{id} (seasons[] with embedded episodes),
//     /movies/{id} (servers[] + download_links[])
//   - episode: /episodes/{id} (servers[] + download_links[])
// Playback terminates at extractor-known hosts (StreamWish, Uqload,
// VidHide/EarnVids, MiraVD, Mwdy, StreamRuby...) via loadExtractor.
// If an episode/movie yields no servers, loadLinks emits one DEBUG
// diagnostic source (Mux test stream) carrying stage markers, the same
// diagnostic pattern used by the repo's EgyBest v1 provider.
version = 3

android {
    namespace = "com.turkmood"
}

cloudstream {
    description = "TurkMood (تركي مود) - Turkish dubbed/translated series and movies via the app's open faselhd API (full-catalog search, paginated lists)"
    authors = listOf("Saher")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}
