package com.brestj

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BrestjPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BrestjProvider())
    }
}
