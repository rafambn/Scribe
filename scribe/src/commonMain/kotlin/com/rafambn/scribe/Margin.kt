package com.rafambn.scribe

/**
 * Lifecycle hooks invoked when a [Scroll] starts and ends.
 */
interface Margin {
    /**
     * Called immediately after [Scribe.newScroll] creates the scroll.
     */
    fun header(scroll: Scroll) {}

    /**
     * Called right before the scroll is sealed.
     */
    fun footer(scroll: Scroll) {}
}
