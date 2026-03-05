package com.rafambn.scribe

interface ScrollEnricher {
    fun onStart(scroll: Scroll) {}
    fun onSeal(scroll: Scroll) {}
}
