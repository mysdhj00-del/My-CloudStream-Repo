package com.animezid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeZidPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeZidProvider())
    }
}
