package com.wecimamobile

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WeCimaMobilePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCimaMobileProvider())
    }
}
