package com.qesset

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class QessetPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(QessetProvider())
    }
}
