package com.viu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ViuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ViuProvider())
    }
}
