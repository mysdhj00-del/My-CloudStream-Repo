package com.fasselhd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FasselHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FasselHDProvider())
    }
}
