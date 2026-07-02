package com.allmanga

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AllmangaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register your provider class instance here
        registerMainAPI(AllmangaProvider())
    }
}