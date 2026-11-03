package com.wecima2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WeCima2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WeCima2Provider())
    }
}
