package scribe.demo

import com.rafambn.scribe.Entry
import com.rafambn.scribe.EntrySaver
import com.rafambn.scribe.Margin
import com.rafambn.scribe.Note
import com.rafambn.scribe.Scroll
import com.rafambn.scribe.Scribe
import com.rafambn.scribe.Urgency
import com.rafambn.scribe.id
import com.rafambn.scribe.seal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    private val activeScrolls = linkedMapOf<String, Scroll>()
    private var scribeInitialized = false
    private var overflowMode = false
    private var uploadedEvents = 0
    private lateinit var mainScribe: Scribe

    private val defaultMargin = object : Margin {
        override fun header(scroll: Scroll) {
            scroll["started_at"] = JsonPrimitive(currentEpochMillis())
            scroll["platform_session"] = JsonPrimitive(platform)
        }

        override fun footer(scroll: Scroll) {
            val startedAt = scroll["started_at"]?.jsonPrimitive?.longOrNull ?: return
            val completedAt = currentEpochMillis()
            scroll["completed_at"] = JsonPrimitive(completedAt)
            scroll["elapsed_ms"] = JsonPrimitive(completedAt - startedAt)
        }
    }

    private val _state = MutableStateFlow(
        ShowcaseUiState(
            streamName = config.stream,
            connectionMessage = "Configured for ${config.baseUrl} and stream ${config.stream}.",
        ),
    )
    val state: StateFlow<ShowcaseUiState> = _state.asStateFlow()

    init {
        mainScribe = createMainScribe()
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
        updateStatus("Ran note(...): a single INFO event went through EntrySaver into the unified stream.")
    }

    fun runFlingNoteScenario() = launchScenario("Second note demo") {
        val scribe = activeMainScribe("second_note") ?: return@launchScenario
        scribe.note(
            tag = "queue",
            message = "Queued retry audit event through the suspending API",
            level = Urgency.DEBUG,
        )
        updateStatus("Ran a second note(...) flow through the suspending API.")
    }

    fun runStringTemplateScenario() = launchScenario("String template scroll demo") {
        val scribe = activeMainScribe("string_template_render") ?: return@launchScenario
        val scroll = openScroll(scribe, id = "template-render-1")
        scroll["demo_name"] = JsonPrimitive("string_template_render")
        scroll["message"] = JsonPrimitive("error on order_id=\$order_id")
        scroll["order_id"] = JsonPrimitive(555)
        sealScroll(scroll, success = true)
        appendTimeline(
            title = "Template message preview",
            detail = "Sent scroll with {message: \"error on order_id=\$order_id\", order_id: 555}.",
            payload = "",
            success = true,
        )
        updateStatus("Ran string-template scroll demo; inspect message + order_id rendering in OpenObserve.")
    }

    fun runCheckoutScenario() = launchScenario("Wide-event scroll demo") {
        val scribe = activeMainScribe("checkout_scroll") ?: return@launchScenario
        val scroll = openScroll(scribe)
        scroll["demo_name"] = JsonPrimitive("checkout_scroll")
        scroll["order_id"] = JsonPrimitive("order-42")
        scroll["gateway"] = JsonPrimitive("stripe")
        scroll["attempt"] = JsonPrimitive(1)
        scroll["retry"] = JsonPrimitive(false)
        scroll["cart"] = json.encodeToJsonElement(
            CheckoutMeta.serializer(),
            CheckoutMeta(
                itemCount = 3,
                subtotalCents = 249_900,
                featureFlag = "wide-events",
            ),
        )
        sealScroll(scroll, success = true)
        updateStatus("Ran newScroll + map writes + seal for a wide checkout event.")
    }

    fun runInspectionScenario() = launchScenario("Scroll map inspection demo") {
        val scribe = activeMainScribe("inspection_scroll") ?: return@launchScenario
        val scroll = openScroll(scribe, id = "ops-demo-42")
        scroll["demo_name"] = JsonPrimitive("inspection_scroll")
        scroll["phase"] = JsonPrimitive("validation")
        scroll["retryable"] = JsonPrimitive(true)
        scroll["attempt"] = JsonPrimitive(2)

        val visibleIds = activeScrolls.keys.toList()
        val phase = scroll["phase"]?.jsonPrimitive?.contentOrNull ?: "missing"
        val removed = scroll.remove("retryable")?.jsonPrimitive?.contentOrNull ?: "null"

        appendTimeline(
            title = "Map read/remove",
            detail = "Custom scroll id ops-demo-42 visible in ${visibleIds.joinToString()} ; phase=$phase ; removed retryable=$removed.",
            payload = "",
            success = true,
        )
        sealScroll(scroll, success = true)
        updateStatus("Ran custom-id scroll demo with map reads/removals and local active-scroll tracking.")
    }

    fun runMarginScenario() = launchScenario("Margin + seal(failure) demo") {
        val scribe = activeMainScribe("margin_scroll") ?: return@launchScenario
        val scroll = openScroll(scribe, id = "inventory-sync-1")
        scroll["demo_name"] = JsonPrimitive("margin_scroll")
        scroll["flow"] = JsonPrimitive("inventory-sync")
        scroll["warehouse"] = JsonPrimitive("gru-1")
        scroll["cache_hit"] = JsonPrimitive(false)
        scroll["failure_reason"] = JsonPrimitive("downstream retry scheduled")
        sealScroll(
            scroll,
            success = false,
        )
        delay(250)
        updateStatus("Ran Margin header/footer hooks with seal(success = false).")
    }

    fun runJsonSerializationScenario() = launchScenario("JSON serialization scroll demo") {
        val scribe = activeMainScribe("json_serialization") ?: return@launchScenario
        val scroll = openScroll(scribe, id = "json-serialization-1")

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

        scroll["demo_name"] = JsonPrimitive("json_serialization")
        scroll["order_snapshot"] = json.encodeToJsonElement(SerializationOrderSnapshot.serializer(), snapshot)
        scroll["order_id"] = JsonPrimitive(snapshot.orderId)
        scroll["buyer_tier"] = JsonPrimitive(snapshot.buyer.tier)
        scroll["primary_sku"] = JsonPrimitive(snapshot.lineItems.first().sku)
        scroll["channel"] = JsonPrimitive(snapshot.metadata["channel"] ?: "unknown")
        scroll["order_item_count"] = JsonPrimitive(snapshot.lineItems.sumOf(SerializationLineItem::quantity))
        scroll["order_tag_count"] = JsonPrimitive(snapshot.tags.size)
        scroll["expected_render_checks"] = JsonPrimitive(
            "order_snapshot.order_id,order_snapshot.buyer.tier,order_snapshot.line_items[0].sku,order_snapshot.metadata.channel,order_id,buyer_tier,primary_sku,channel,order_item_count,order_tag_count",
        )

        sealScroll(scroll, success = true)
        updateStatus("Ran JSON serialization demo with a nested object payload for OpenObserve inspection.")
    }

    fun runEntrySaverScenario() = launchScenario("Unified EntrySaver demo") {
        val scribe = activeMainScribe("entry_saver_demo") ?: return@launchScenario
        scribe.note(
            tag = "auth",
            message = "Session accepted for staff dashboard",
            level = Urgency.INFO,
        )
        val scroll = openScroll(scribe, id = "session-audit")
        scroll["demo_name"] = JsonPrimitive("entry_saver_demo")
        scroll["role"] = JsonPrimitive("support")
        scroll["elevated_access"] = JsonPrimitive(true)
        sealScroll(scroll, success = true)
        updateStatus("Ran a mixed note + scroll demo through one EntrySaver path.")
    }

    fun runOverflowScenario() = launchScenario("Overflow demo") {
        val scribe = activeMainScribe("overflow_demo") ?: return@launchScenario
        val baseline = uploadedEvents
        val attempted = 12

        overflowMode = true
        repeat(attempted) { index ->
            scribe.note(
                tag = "buffer",
                message = "burst event #$index",
                level = if (index % 3 == 0) Urgency.WARN else Urgency.INFO,
            )
        }
        delay(1800)
        overflowMode = false

        val delivered = uploadedEvents - baseline
        appendTimeline(
            title = "Overflow result",
            detail = "Attempted $attempted notes with channel capacity $MAIN_CHANNEL_CAPACITY and DROP_OLDEST; delivered $delivered.",
            payload = "",
            success = delivered < attempted,
        )
        updateStatus("Ran overflow demo with Channel(..., onBufferOverflow = DROP_OLDEST).")
    }

    fun runSaverFailureScenario() = launchScenario("Saver error demo") {
        val scribe = activeMainScribe("saver_failure") ?: return@launchScenario
        scribe.note(
            tag = "saver_failure",
            message = "Intentional saver failure probe",
            level = Urgency.WARN,
        )
        updateStatus("Saver failure demo ran; onSaver callback captures the injected failure.")
    }

    fun runRetireScenario() = launchScenario("retire() demo") {
        val scribe = activeMainScribe("retire_demo") ?: return@launchScenario
        scribe.note("shutdown", "retire() with light queue", Urgency.INFO)
        val started = currentEpochMillis()
        scribe.retire()
        val elapsed = currentEpochMillis() - started

        activeScrolls.clear()
        _state.update { it.copy(isRetired = true) }
        refreshActiveScrolls()
        appendTimeline(
            title = "retire()",
            detail = "retire() finished in ${elapsed}ms and retired the shared demo Scribe instance.",
            payload = "",
            success = true,
        )
        updateStatus("The shared demo Scribe is retired. Press Recreate Scribe before sending more messages.")
    }

    fun runPlanRetireScenario() = launchScenario("retire() with backlog demo") {
        val scribe = activeMainScribe("retire_backlog_demo") ?: return@launchScenario
        repeat(6) { index ->
            scribe.note("shutdown", "drain probe #$index", Urgency.INFO)
        }
        val started = currentEpochMillis()
        scribe.retire()
        val elapsed = currentEpochMillis() - started

        activeScrolls.clear()
        _state.update { it.copy(isRetired = true) }
        refreshActiveScrolls()
        appendTimeline(
            title = "retire() with backlog",
            detail = "retire() took ${elapsed}ms after a small queued backlog.",
            payload = "",
            success = true,
        )
        updateStatus("The shared demo Scribe is retired after draining queued work. Press Recreate Scribe to continue.")
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
            detail = "A new shared demo Scribe runtime was hired after retirement.",
            payload = "",
            success = true,
        )
    }

    fun close() {
        scope.launch {
            runCatching { Scribe.retire() }
        }
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

    private fun createMainScribe(): Scribe {
        if (!scribeInitialized) {
            Scribe.inscribe {
                shelves = listOf(
                    failingEntrySaver(),
                    entryUploadSaver("shared_session", "EntrySaver"),
                )
                imprint = sampleImprint(platform) + mapOf(
                    "stream" to JsonPrimitive(config.stream),
                    "session_kind" to JsonPrimitive("persistent-demo"),
                )
                margins = defaultMargin
                onIgnition = { throwable ->
                    _state.update {
                        it.copy(ignitionMessage = "onIgnition captured ${throwable.message}")
                    }
                }
            }
            scribeInitialized = true
        }

        Scribe.hire(
            scope = scope,
            channel = Channel(capacity = MAIN_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST),
            onSaver = { saver, entry, error ->
                appendSaverError(
                    "Saver failure in ${saver::class.simpleName ?: "Saver"} for ${entryKind(entry)}: ${error.message ?: error}",
                )
            },
        )
        return Scribe
    }

    private fun failingEntrySaver(): EntrySaver = EntrySaver { entry ->
        if (entry is Note && entry.tag == "saver_failure") {
            error("Intentional saver failure from showcase demo")
        }
    }

    private fun entryUploadSaver(
        demoName: String,
        saverType: String,
    ): EntrySaver = EntrySaver { entry ->
        uploadRecord(entry, demoName, saverType)
    }

    private suspend fun uploadRecord(
        entry: Entry,
        demoName: String,
        saverType: String,
    ) {
        if (overflowMode) {
            delay(220)
        }

        val record = payloadFromEntry(
            entry = entry,
            demoName = demoName,
            platform = platform,
            saverType = saverType,
            appVersion = appVersion,
            uploadedAt = currentEpochMillis(),
        )

        uploadedEvents += 1
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
        updateActiveScrolls(activeScrolls.keys.toList())
    }

    private fun openScroll(scribe: Scribe, id: String? = null): Scroll {
        val scroll = scribe.newScroll(id = id)
        activeScrolls[scroll.id] = scroll
        refreshActiveScrolls()
        return scroll
    }

    private suspend fun sealScroll(scroll: Scroll, success: Boolean) {
        scroll.seal(success = success)
        activeScrolls.remove(scroll.id)
        refreshActiveScrolls()
    }

    private fun entryKind(entry: Entry): String =
        when (entry) {
            is Note -> "note"
            else -> "scroll"
        }

    private companion object {
        const val MAIN_CHANNEL_CAPACITY = 2
    }
}
