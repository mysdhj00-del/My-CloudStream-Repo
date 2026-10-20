package com.turkdub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TurkDubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TurkDubProvider())
    }
}
