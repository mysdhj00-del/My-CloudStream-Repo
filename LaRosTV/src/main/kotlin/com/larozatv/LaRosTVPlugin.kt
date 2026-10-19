package com.larozatv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LaRosTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LaRosTVProvider())
    }
}
