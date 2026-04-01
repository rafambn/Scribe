package scribe.demo

import com.rafambn.scribe.Entry
import com.rafambn.scribe.EntrySaver
import com.rafambn.scribe.Margin
import com.rafambn.scribe.Note
import com.rafambn.scribe.NoteSaver
import com.rafambn.scribe.Saver
import com.rafambn.scribe.Scroll
import com.rafambn.scribe.ScrollSaver
import com.rafambn.scribe.Scribe
import com.rafambn.scribe.ScribeDeliveryConfig
import com.rafambn.scribe.Urgency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

class ShowcaseController {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
    private val config = OpenObserveConfig()
    private val client = OpenObserveClient(config, json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val appVersion = "testApp-showcase"
    private val platform = platformName()
    private val defaultMargin = object : Margin {
        override fun header(scroll: Scroll) {
            scroll.writeNumber("startedAt", currentEpochMillis())
            scroll.writeString("platform_session", platform)
        }

        override fun footer(scroll: Scroll) {
            val startedAt = scroll.read("startedAt")?.jsonPrimitive?.longOrNull ?: return
            val completedAt = currentEpochMillis()
            scroll.writeNumber("completedAt", completedAt)
            scroll.writeNumber("elapsedMs", completedAt - startedAt)
        }
    }
    private var mainScribe: Scribe = createMainScribe()

    private val _state = MutableStateFlow(
        ShowcaseUiState(
            streamName = config.stream,
            connectionMessage = "Configured for ${config.baseUrl} and stream ${config.stream}.",
        ),
    )
    val state: StateFlow<ShowcaseUiState> = _state.asStateFlow()

    init {
        refreshConnection()
    }

    fun refreshConnection() {
        scope.launch {
            _state.update {
                it.copy(
                    connectionState = ConnectionState.CHECKING,
                    connectionMessage = "Checking ${config.baseUrl}...",
                )
            }
            val result = client.ping()
            _state.update {
                if (result.isSuccess) {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        connectionMessage = result.getOrThrow(),
                    )
                } else {
                    it.copy(
                        connectionState = ConnectionState.FAILED,
                        connectionMessage = result.exceptionOrNull()?.message ?: "Failed to reach OpenObserve.",
                    )
                }
            }
        }
    }

    fun runNoteScenario() = launchScenario("Suspending note demo") {
        val scribe = activeMainScribe("single_note") ?: return@launchScenario
        scribe.note(
            tag = "checkout",
            message = "Started checkout for premium customer",
            level = Urgency.INFO,
        )
        updateStatus("Ran note(...): a single INFO event went through NoteSaver into the unified stream.")
    }

    fun runFlingNoteScenario() = launchScenario("Best-effort note demo") {
        val scribe = activeMainScribe("best_effort_note") ?: return@launchScenario
        scribe.flingNote(
            tag = "queue",
            message = "Queued fire-and-forget retry audit event",
            level = Urgency.DEBUG,
        )
        updateStatus("Ran flingNote(...): best-effort note dispatch attempted.")
    }

    fun runStringTemplateScenario() = launchScenario("String template scroll demo") {
        val scribe = activeMainScribe("string_template_render") ?: return@launchScenario
        val scroll = scribe.unrollScroll(id = "template-render-1")
        updateActiveScrolls(scribe.seekScrolls().map(Scroll::id))
        scroll.writeString("demo_name", "string_template_render")
        scroll.writeString("message", "error on orderId=\$ordemId")
        scroll.writeNumber("ordemId", 555)
        scroll.seal(success = true)
        refreshActiveScrolls()
        appendTimeline(
            title = "Template message preview",
            detail = "Sent scroll with {message: \"error on orderId=\$ordemId\", ordemId: 555}.",
            payload = "",
            success = true,
        )
        updateStatus("Ran string-template scroll demo; inspect message + ordemId rendering in OpenObserve.")
    }

    fun runCheckoutScenario() = launchScenario("Wide-event scroll demo") {
        val scribe = activeMainScribe("checkout_scroll") ?: return@launchScenario
        val scroll = scribe.unrollScroll()
        updateActiveScrolls(scribe.seekScrolls().map(Scroll::id))
        scroll.writeString("demo_name", "checkout_scroll")
        scroll.writeString("order_id", "order-42")
        scroll.writeString("gateway", "stripe")
        scroll.writeNumber("attempt", 1)
        scroll.writeBoolean("retry", false)
        scroll.writeSerializable(
            "cart",
            CheckoutMeta(
                itemCount = 3,
                subtotalCents = 249_900,
                featureFlag = "wide-events",
            ),
        )
        scroll.seal(success = true)
        refreshActiveScrolls()
        updateStatus("Ran unrollScroll + writeSerializable + seal for a wide checkout event.")
    }

    fun runInspectionScenario() = launchScenario("Scroll inspection demo") {
        val scribe = activeMainScribe("inspection_scroll") ?: return@launchScenario
        val scroll = scribe.unrollScroll(id = "ops-demo-42")
        scroll.writeString("demo_name", "inspection_scroll")
        scroll.writeString("phase", "validation")
        scroll.writeBoolean("retryable", true)
        scroll.writeNumber("attempt", 2)
        val visibleIds = scribe.seekScrolls().map(Scroll::id)
        val phase = scroll.read("phase")?.jsonPrimitive?.content ?: "missing"
        val erased = scroll.erase("retryable")?.jsonPrimitive?.content ?: "null"
        updateActiveScrolls(visibleIds)
        appendTimeline(
            title = "seekScrolls/read/erase",
            detail = "Custom scroll id ops-demo-42 was visible in ${visibleIds.joinToString()} ; phase=$phase ; erased retryable=$erased.",
            payload = "",
            success = true,
        )
        scroll.seal(success = true)
        refreshActiveScrolls()
        updateStatus("Ran custom-id scroll demo with seekScrolls(), read(), and erase().")
    }

    fun runMarginScenario() = launchScenario("Margin and looseSeal demo") {
        val scribe = activeMainScribe("margin_scroll") ?: return@launchScenario
        val scroll = scribe.unrollScroll(id = "inventory-sync-1")
        updateActiveScrolls(scribe.seekScrolls().map(Scroll::id))
        scroll.writeString("demo_name", "margin_scroll")
        scroll.writeString("flow", "inventory-sync")
        scroll.writeString("warehouse", "gru-1")
        scroll.writeBoolean("cacheHit", false)
        scroll.looseSeal(success = false, error = IllegalStateException("downstream retry scheduled"))
        delay(250)
        refreshActiveScrolls()
        updateStatus("Ran Margin header/footer hooks and looseSeal(...) with an error payload.")
    }

    fun runJsonSerializationScenario() = launchScenario("JSON serialization scroll demo") {
        val scribe = activeMainScribe("json_serialization") ?: return@launchScenario
        val scroll = scribe.unrollScroll(id = "json-serialization-1")
        updateActiveScrolls(scribe.seekScrolls().map(Scroll::id))
        val snapshot = SerializationOrderSnapshot(
            orderId = "order-555",
            buyer = SerializationBuyer(
                id = "buyer-123",
                tier = "gold",
                email = "buyer-123@example.com",
            ),
            lineItems = listOf(
                SerializationLineItem(sku = "SKU-CHAIR-42", quantity = 1, unitPriceCents = 129_900),
                SerializationLineItem(sku = "SKU-LAMP-10", quantity = 2, unitPriceCents = 24_990),
            ),
            payment = SerializationPayment(
                method = "credit_card",
                installments = 3,
                currency = "USD",
            ),
            tags = listOf("openobserve", "serialization-test", "nested-object"),
            metadata = mapOf(
                "channel" to "web",
                "experiment" to "openobserve-json-object",
            ),
        )
        scroll.writeString("demo_name", "json_serialization")
        scroll.writeSerializable("order_snapshot", snapshot)
        scroll.writeString("order_id", snapshot.orderId)
        scroll.writeString("buyer_tier", snapshot.buyer.tier)
        scroll.writeString("primary_sku", snapshot.lineItems.first().sku)
        scroll.writeString("channel", snapshot.metadata["channel"] ?: "unknown")
        scroll.writeSerializable(
            "expected_render_checks",
            listOf(
                "order_snapshot.orderId",
                "order_snapshot.buyer.tier",
                "order_snapshot.lineItems[0].sku",
                "order_snapshot.metadata.channel",
                "order_id",
                "buyer_tier",
                "primary_sku",
                "channel",
            ),
        )
        scroll.seal(success = true)
        refreshActiveScrolls()
        updateStatus("Ran JSON serialization demo with a nested object payload for OpenObserve inspection.")
    }

    fun runEntrySaverScenario() = launchScenario("Unified EntrySaver demo") {
        val scribe = activeMainScribe("entry_saver_demo") ?: return@launchScenario
        scribe.note(
            tag = "auth",
            message = "Session accepted for staff dashboard",
            level = Urgency.INFO,
        )
        val scroll = scribe.unrollScroll(id = "session-audit")
        scroll.writeString("demo_name", "entry_saver_demo")
        scroll.writeString("role", "support")
        scroll.writeBoolean("elevated_access", true)
        scroll.seal(success = true)
        refreshActiveScrolls()
        updateStatus("Ran a mixed note + scroll demo through EntrySaver into one stream.")
    }

    fun runOverflowScenario() = launchScenario("Overflow demo") {
        val demoName = "overflow_demo"
        val deliveredMessages = mutableListOf<String>()
        val slowSaver = EntrySaver {
            delay(600)
        }
        val trackingSaver = entryUploadSaver(demoName, "EntrySaver") { record ->
            record["message"]?.jsonPrimitive?.contentOrNull?.let(deliveredMessages::add)
        }
        withScribe(
            demoName = demoName,
            shelves = listOf(slowSaver, trackingSaver),
            deliveryConfig = ScribeDeliveryConfig(
                bufferSize = 1,
                overflowStrategy = BufferOverflow.DROP_LATEST,
            ),
        ) { scribe ->
            scribe.flingNote("buffer", "first event survives", Urgency.INFO)
            scribe.flingNote("buffer", "second event survives", Urgency.INFO)
            scribe.flingNote("buffer", "third event is dropped", Urgency.WARN)
            delay(1_600)
        }
        appendTimeline(
            title = "Buffer overflow result",
            detail = "Delivered messages: ${deliveredMessages.joinToString()}",
            payload = "",
            success = deliveredMessages.size == 2 && deliveredMessages.none { it.contains("third") },
        )
        updateStatus("Ran ScribeDeliveryConfig with bufferSize=1 and DROP_LATEST to show overflow behavior.")
    }

    fun runSaverFailureScenario() = launchScenario("Saver error demo") {
        val demoName = "saver_failure"
        val failingSaver = EntrySaver {
            error("Intentional saver failure for the demo")
        }
        withScribe(
            demoName = demoName,
            shelves = listOf(failingSaver, entryUploadSaver(demoName, "EntrySaver")),
            deliveryConfig = ScribeDeliveryConfig(
                onSaverError = { _, entry, error ->
                    val message = "onSaverError captured ${entryKind(entry)} failure: ${error.message}"
                    appendSaverError(message)
                },
            ),
        ) { scribe ->
            scribe.flingNote("payments", "Primary saver failed but upload still continued", Urgency.ERROR)
        }
        updateStatus("Ran onSaverError demo: one saver failed and the next saver still handled the entry.")
    }

    fun runRetireScenario() = launchScenario("retire() demo") {
        val scribe = activeMainScribe("retire_demo") ?: return@launchScenario
        scribe.flingNote("shutdown", "retire returns immediately", Urgency.INFO)
        val started = currentEpochMillis()
        scribe.retire()
        val elapsed = currentEpochMillis() - started
        _state.update { it.copy(isRetired = true) }
        appendTimeline(
            title = "retire()",
            detail = "retire() returned in ${elapsed}ms and retired the shared demo Scribe instance.",
            payload = "",
            success = elapsed < 200,
        )
        refreshActiveScrolls()
        updateStatus("The shared demo Scribe is retired. Press Recreate Scribe before sending more messages.")
    }

    fun runPlanRetireScenario() = launchScenario("planRetire() demo") {
        val demoName = "plan_retire_demo"
        val slowSaver = EntrySaver {
            delay(700)
        }
        withScribe(
            demoName = demoName,
            shelves = listOf(slowSaver, entryUploadSaver(demoName, "EntrySaver")),
        ) { scribe ->
            scribe.flingNote("shutdown", "planRetire waits for drain", Urgency.INFO)
            val started = currentEpochMillis()
            scribe.planRetire()
            val elapsed = currentEpochMillis() - started
            appendTimeline(
                title = "planRetire()",
                detail = "planRetire() took ${elapsed}ms because it waited for the queued event to flush.",
                payload = "",
                success = elapsed >= 650,
            )
        }
        updateStatus("Ran planRetire(): it blocked until the queued entry had been delivered.")
    }

    fun wireIgnitionScenario() = launchScenario("onIgnition wiring") {
        val scribe = activeMainScribe("ignition_hook") ?: return@launchScenario
        scribe.note(
            tag = "ignition",
            message = "onIgnition callback is configured; the demo avoids firing an uncaught exception.",
            level = Urgency.INFO,
        )
        _state.update {
            it.copy(
                ignitionMessage = "onIgnition is configured in this demo build. Triggering it live would terminate the app, so the showcase documents the hook instead of crashing itself.",
            )
        }
        updateStatus("Configured onIgnition safely without terminating the showcase process.")
    }

    fun recreateMainScribe() = launchScenario("Recreate Scribe") {
        if (!_state.value.isRetired) {
            updateStatus("The shared demo Scribe is already active.")
            appendTimeline(
                title = "Recreate Scribe",
                detail = "The shared demo Scribe was already active, so no recreation was needed.",
                payload = "",
                success = true,
            )
            return@launchScenario
        }
        mainScribe = createMainScribe()
        _state.update { it.copy(isRetired = false) }
        refreshActiveScrolls()
        updateStatus("The shared demo Scribe was recreated and can send messages again.")
        appendTimeline(
            title = "Recreate Scribe",
            detail = "A new shared demo Scribe instance was created after retirement.",
            payload = "",
            success = true,
        )
    }

    fun close() {
        Unit
    }

    private fun launchScenario(label: String, block: suspend () -> Unit) {
        scope.launch {
            gate.withLock {
                _state.update { it.copy(isBusy = true, busyLabel = label) }
                try {
                    block()
                } catch (error: Throwable) {
                    appendTimeline(
                        title = label,
                        detail = error.message ?: error.toString(),
                        payload = "",
                        success = false,
                    )
                    updateStatus("Scenario failed: ${error.message ?: error}")
                } finally {
                    _state.update { it.copy(isBusy = false, busyLabel = "") }
                }
            }
        }
    }

    private suspend fun withScribe(
        demoName: String,
        shelves: List<Saver<*>>,
        deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
        margins: Margin? = null,
        onIgnition: ((Throwable) -> Unit)? = null,
        block: suspend (Scribe) -> Unit,
    ) {
        val scribe = makeScribe(
            demoName = demoName,
            shelves = shelves,
            deliveryConfig = deliveryConfig,
            margins = margins,
            onIgnition = onIgnition,
        )
        try {
            block(scribe)
        } finally {
            scribe.planRetire()
        }
    }

    private fun activeMainScribe(demoName: String): Scribe? {
        if (_state.value.isRetired) {
            val message = "The shared demo Scribe is retired. Press Recreate Scribe before running $demoName."
            updateStatus(message)
            appendTimeline(
                title = "Scribe retired",
                detail = message,
                payload = "",
                success = false,
            )
            return null
        }
        return mainScribe
    }

    private fun createMainScribe(): Scribe =
        Scribe(
            shelves = listOf(entryUploadSaver("shared_session", "EntrySaver")),
            imprint = sampleImprint(platform) + mapOf(
                "stream" to JsonPrimitive(config.stream),
                "session_kind" to JsonPrimitive("persistent-demo"),
            ),
            margins = defaultMargin,
            onIgnition = { throwable ->
                _state.update {
                    it.copy(ignitionMessage = "onIgnition captured ${throwable.message}")
                }
            },
        )

    private fun makeScribe(
        demoName: String,
        shelves: List<Saver<*>>,
        deliveryConfig: ScribeDeliveryConfig = ScribeDeliveryConfig(),
        margins: Margin? = null,
        onIgnition: ((Throwable) -> Unit)? = null,
    ): Scribe =
        Scribe(
            shelves = shelves,
            imprint = sampleImprint(platform) + mapOf(
                "demo_name" to JsonPrimitive(demoName),
                "stream" to JsonPrimitive(config.stream),
            ),
            deliveryConfig = deliveryConfig,
            margins = margins,
            onIgnition = onIgnition,
        )

    private fun noteUploadSaver(
        demoName: String,
        saverType: String,
        onRecord: (OpenObservePayload) -> Unit = {},
    ): NoteSaver = NoteSaver { note ->
        uploadRecord(note, demoName, saverType, onRecord)
    }

    private fun scrollUploadSaver(
        demoName: String,
        saverType: String,
        onRecord: (OpenObservePayload) -> Unit = {},
    ): ScrollSaver = ScrollSaver { scroll ->
        uploadRecord(scroll, demoName, saverType, onRecord)
    }

    private fun entryUploadSaver(
        demoName: String,
        saverType: String,
        onRecord: (OpenObservePayload) -> Unit = {},
    ): EntrySaver = EntrySaver { entry ->
        uploadRecord(entry, demoName, saverType, onRecord)
    }

    private suspend fun uploadRecord(
        entry: Entry,
        demoName: String,
        saverType: String,
        onRecord: (OpenObservePayload) -> Unit,
    ) {
        val record = payloadFromEntry(
            entry = entry,
            demoName = demoName,
            platform = platform,
            saverType = saverType,
            appVersion = appVersion,
            uploadedAt = currentEpochMillis(),
        )
        onRecord(record)
        val payload = client.prettyRecord(record)
        val result = client.upload(record)
        _state.update {
            it.copy(
                lastPayload = payload,
                lastUploadMessage = result.getOrElse { error -> error.message ?: "Upload failed." },
                connectionState = if (result.isSuccess) ConnectionState.CONNECTED else ConnectionState.FAILED,
            )
        }
        appendTimeline(
            title = "${payloadEventKind(record)} via $saverType",
            detail = "${recordSummary(record)}. ${result.getOrElse { error -> error.message ?: "Upload failed." }}",
            payload = payload,
            success = result.isSuccess,
        )
    }

    private fun appendTimeline(title: String, detail: String, payload: String, success: Boolean) {
        _state.update {
            it.copy(
                timeline = listOf(TimelineItem(title, detail, payload, success)) + it.timeline.take(19),
            )
        }
    }

    private fun appendSaverError(message: String) {
        _state.update {
            it.copy(
                saverErrors = listOf(message) + it.saverErrors.take(5),
            )
        }
        appendTimeline(
            title = "Saver failure captured",
            detail = message,
            payload = "",
            success = false,
        )
    }

    private fun updateStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
    }

    private fun updateActiveScrolls(ids: List<String>) {
        _state.update { it.copy(activeScrollIds = ids) }
    }

    private fun refreshActiveScrolls() {
        if (_state.value.isRetired) {
            updateActiveScrolls(emptyList())
            return
        }
        updateActiveScrolls(mainScribe.seekScrolls().map(Scroll::id))
    }

    private fun entryKind(entry: Entry): String =
        when (entry) {
            is Note -> "note"
            else -> "scroll"
        }
}
