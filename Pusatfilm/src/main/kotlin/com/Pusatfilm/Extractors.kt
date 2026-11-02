package com.pusatfilm

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.SubtitleFile

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val links = document.select("ul#dropdown-server li a")

        links.amap { element ->
            val encodedFrame = element.attr("data-frame")
            val decodedUrl = base64Decode(encodedFrame)
            loadExtractor(decodedUrl, "$mainUrl/", subtitleCallback, callback)
        }
    }
}
