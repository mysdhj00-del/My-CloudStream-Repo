package com.wecima

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WeCimaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCimaProvider())
    }
}
