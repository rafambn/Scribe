package com.rafambn.scribe

import com.rafambn.scribe.internal.nowEpochMs

class TimestampEnricher : ScrollEnricher {
    override fun onStart(scroll: Scroll) {
        scroll.putNumber("startedAtEpochMs", nowEpochMs())
    }

    override fun onSeal(scroll: Scroll) {
        scroll.putNumber("sealedAtEpochMs", nowEpochMs())
    }
}
