package com.isk

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IskPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IskProvider())
    }
}
