package com.anichin

import com.lagradost.cloudstream3.extractors.StreamWishExtractor

class Shorticu : StreamWishExtractor() {
    override val name: String = "Shorticu"
    override val mainUrl: String = "https://short.icu"
}

class Turbovidhls : StreamWishExtractor() {
    override val name: String = "Turbovidhls"
    override val mainUrl: String = "https://turbovidhls.com"
}
