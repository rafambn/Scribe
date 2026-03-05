package com.rafambn.scribe

interface Margin {
    fun header(scroll: Scroll) {}
    fun footer(scroll: Scroll) {}
}
