package com.anichin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class Anichin : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    private val mainUrlJson = "https://raw.githubusercontent.com/Cwemeh/cloudCX-V1/builds/Website.json"
    private var directUrl: String? = null
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "anime/?order=update&page=%d" to "Terbaru",
        "anime/?order=popular&page=%d" to "Populer",
        "anime/?status=ongoing&page=%d" to "Ongoing",
        "anime/?status=completed&page=%d" to "Completed",
        "anime/?type=donghua&page=%d" to "Donghua"
    )

    private suspend fun loadMainUrlIfNeeded() {
        if (directUrl != null) return
        try {
            val response = app.get(mainUrlJson).text
            val json = JSONObject(response)
            val array = json.optJSONArray("anichin")
            val newUrl = array?.optString(0)?.removeSuffix("/")

            if (!newUrl.isNullOrBlank()) {
                mainUrl = newUrl
                directUrl = newUrl
            }
        } catch (_: Exception) { }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadMainUrlIfNeeded()
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.bs, div.bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val bsx = this.selectFirst("div.bsx") ?: this
        val a = bsx.selectFirst("a") ?: return null
        val rawHref = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(rawHref)
        val title = (a.attr("title").takeIf { it.isNotBlank() }
            ?: bsx.selectFirst("div.tt h2, div.tt, h2")?.text()
            ?: a.text()).trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(bsx.selectFirst("img")?.getImageAttr())
        val epText = bsx.selectFirst("span.epx, div.bt span.ep, span.ep")?.text()?.trim()

        val epNum = epText?.let { Regex("(?i)(?:ep|episode)\\s*(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: Regex("(?i)(?:ep|episode)\\s*(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epNum != null) {
                addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadMainUrlIfNeeded()
        var items = app.get("$mainUrl/anime/?title=$query").document
            .select("article.bs, div.bsx")
            .mapNotNull { it.toSearchResult() }

        if (items.isEmpty()) {
            items = app.get("$mainUrl/?s=$query").document
                .select("article.bs, div.bsx")
                .mapNotNull { it.toSearchResult() }
        }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document

        // Jika halaman yang dibuka adalah halaman episode langsung, cari link series-nya di breadcrumbs
        if (document.select("div.eplister li").isEmpty()) {
            val seriesUrl = document.select("div.ts-breadcrumb span a[href*='/anime/'], div.ts-breadcrumb span a[href]:not([href='/']):not([href*='-episode-'])")
                .lastOrNull()?.attr("href")
                ?: document.selectFirst("div.all-episodes a, div.naveps a:contains(All)")?.attr("href")

            if (!seriesUrl.isNullOrBlank() && fixUrl(seriesUrl) != fixUrl(url)) {
                return load(fixUrl(seriesUrl))
            }
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(document.selectFirst("div.thumb img, div.bigcontent img")?.getImageAttr())
        val description = document.selectFirst("div.entry-content[itemprop=description] p, div.synp div.entry-content, div.entry-content")?.text()?.trim()
        val tags = document.select("div.genxed a, div.genres-content a").map { it.text().trim() }
        val rating = document.selectFirst("div.rating strong, div.rating-prc strong, div.num")?.text()
            ?.replace("Rating", "")?.trim()

        val year = document.select("div.spe span:contains(Tanggal Rilis), div.info-content span:contains(Released)")
            .text().let { Regex("(\\d{4})").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val statusText = document.select("div.spe span:contains(Status), div.info-content span:contains(Status)").text()
        val showStatus = when {
            statusText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }

        val episodes = document.select("div.eplister li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val numText = li.selectFirst("div.epl-num")?.text()?.trim()
            val titleText = li.selectFirst("div.epl-title")?.text()?.trim()
            val epNum = numText?.replace(Regex("(?i)\\s*END"), "")?.toIntOrNull()
                ?: Regex("(?i)Episode\\s*(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = titleText ?: numText?.let { "Episode $it" }
                this.episode = epNum
                this.posterUrl = poster
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.showStatus = showStatus
            addScore(rating)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadMainUrlIfNeeded()
        val document = app.get(data).document

        // 1. Iframe langsung di halaman utama player
        val defaultIframe = document.selectFirst("div.player-embed iframe, div.video-content iframe, div#embed_holder iframe, iframe")
            ?.getIframeAttr()
        if (!defaultIframe.isNullOrBlank()) {
            loadExtractor(httpsify(defaultIframe), "$directUrl/", subtitleCallback, callback)
        }

        // 2. Dropdown Mirror (Base64 Encoded iframes)
        document.select("select.mirror option, select[name=mirror] option").amap { option ->
            val rawBase64 = option.attr("value").trim()
            if (rawBase64.isNotBlank()) {
                val decodedHtml = try {
                    String(Base64.decode(rawBase64, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    try {
                        String(java.util.Base64.getDecoder().decode(rawBase64), Charsets.UTF_8)
                    } catch (_: Exception) { "" }
                }

                if (decodedHtml.isNotBlank()) {
                    val iframeSrc = Jsoup.parse(decodedHtml).selectFirst("iframe")?.getIframeAttr()
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(httpsify(iframeSrc), "$directUrl/", subtitleCallback, callback)
                    }
                }
            }
        }

        // 3. Tab server / data-src
        document.select("ul#server-list li, div.server-item").amap { item ->
            val src = item.attr("data-src").takeIf { it.isNotBlank() } ?: item.attr("data-video")
            if (!src.isNullOrBlank()) {
                loadExtractor(httpsify(src), "$directUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
