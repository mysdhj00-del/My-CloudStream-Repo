package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EgyDeadPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EgyDeadProvider())
    }
}
