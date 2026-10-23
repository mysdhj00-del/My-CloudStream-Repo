package com.movizland

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovizLandPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovizLandProvider())
    }
}
