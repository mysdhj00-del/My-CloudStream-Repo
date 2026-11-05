package com.animeverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeVersePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeVerseProvider())
    }
}
