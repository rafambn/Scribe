package com.rafambn.scribe

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WasmWasiExceptionHandlerTest {
    @Test
    fun onIgnition_is_rejected_with_an_explicit_platform_limitation() {
        val scribe = scribeWithScrollShelves(
            Archivist { },
            startProcessing = false,
            onIgnition = {},
        )

        val error = assertFailsWith<UnsupportedOperationException> { scribe.hire() }
        assertTrue(error.message.orEmpty().contains("wasmWasi"))
    }
}
