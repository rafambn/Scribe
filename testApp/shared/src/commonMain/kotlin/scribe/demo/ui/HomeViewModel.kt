package scribe.demo.ui

import com.rafambn.scribe.ScrollEntry
import com.rafambn.scribe.Scribe
import com.rafambn.scribe.Scroll
import com.rafambn.scribe.id
import com.rafambn.scribe.seal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import scribe.demo.currentEpochMillis
import scribe.demo.data.CheckoutMeta
import scribe.demo.data.SerializationBuyer
import scribe.demo.data.SerializationLineItem
import scribe.demo.data.SerializationOrderSnapshot
import scribe.demo.data.SerializationPayment
import scribe.demo.data.TimelineItem
import scribe.demo.data.consoleRecordFromEntry
import scribe.demo.data.payloadEventKind
import scribe.demo.data.recordSummary
import scribe.demo.platformName
import scribe.demo.scribe.AppScribe
import kotlin.collections.set
import kotlin.time.Duration.Companion.milliseconds

class HomeViewModel {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val appVersion = "testApp-showcase"
    private val platform = platformName()
    private val activeScrolls = linkedMapOf<String, Scroll>()
    private var printedEvents = 0

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val appScribe = AppScribe { entry -> handleRecord(entry) }

    init {
        appScribe.hire(
            scope = scope,
            channel = Channel(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST),
            onSaver = { saver, entry, error ->
                appendSaverError(
                    "Saver failure in ${saver::class.simpleName ?: "Saver"} for ${entryKind()}: ${error.message ?: error}",
                )
            },
        )
    }

    fun runQuickScrollScenario() = launchScenario("Quick scroll emission demo") {
        emitQuickScroll(
            tag = "checkout",
            message = "Started checkout for premium customer",
            level = "INFO",
        )
        updateStatus("Ran a quick scroll: one immediately sealed event printed through EntrySaver.")
    }

    fun runSecondQuickScrollScenario() = launchScenario("Second quick scroll demo") {
        emitQuickScroll(
            tag = "queue",
            message = "Queued retry audit event as an immediately sealed scroll",
            level = "DEBUG",
        )
        updateStatus("Ran a second immediately sealed scroll flow.")
    }

    fun runStringTemplateScenario() = launchScenario("String template scroll demo") {
        val scroll = openScroll(appScribe, id = "template-render-1")
        scroll["demo_name"] = JsonPrimitive("string_template_render")
        scroll["message"] = JsonPrimitive("error on order_id=\$order_id")
        scroll["order_id"] = JsonPrimitive(555)
        sealScroll(scroll, appScribe)
        appendTimeline(
            title = "Template message preview",
            detail = "Sent scroll with {message: \"error on order_id=\$order_id\", order_id: 555}.",
            payload = "",
            success = true,
        )
        updateStatus("Ran string-template scroll demo; inspect message + order_id in console output.")
    }

    fun runCheckoutScenario() = launchScenario("Wide-event scroll demo") {
        val scroll = openScroll(appScribe)
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
        sealScroll(scroll, appScribe)
        updateStatus("Ran newScroll + map writes + seal for a wide checkout event.")
    }

    fun runInspectionScenario() = launchScenario("Scroll map inspection demo") {
        val scroll = openScroll(appScribe, id = "ops-demo-42")
        scroll["demo_name"] = JsonPrimitive("inspection_scroll")
        scroll["phase"] = JsonPrimitive("validation")
        scroll["retryable"] = JsonPrimitive(true)
        scroll["attempt"] = JsonPrimitive(2)

        val visibleIds = activeScrolls.keys.toList()
        val phase = scroll["phase"]?.toString() ?: "missing"
        val removed = scroll.remove("retryable")?.toString() ?: "null"

        appendTimeline(
            title = "Map read/remove",
            detail = "Custom scroll id ops-demo-42 visible in ${visibleIds.joinToString()} ; phase=$phase ; removed retryable=$removed.",
            payload = "",
            success = true,
        )
        sealScroll(scroll, appScribe)
        updateStatus("Ran custom-id scroll demo with map reads/removals and local active-scroll tracking.")
    }

    fun runMarginScenario() = launchScenario("Margin + seal(failure) demo") {
        val scroll = openScroll(appScribe, id = "inventory-sync-1")
        scroll["demo_name"] = JsonPrimitive("margin_scroll")
        scroll["flow"] = JsonPrimitive("inventory-sync")
        scroll["warehouse"] = JsonPrimitive("gru-1")
        scroll["cache_hit"] = JsonPrimitive(false)
        scroll["failure_reason"] = JsonPrimitive("downstream retry scheduled")
        scroll["success"] = JsonPrimitive(false)
        sealScroll(scroll, appScribe)
        delay(250.milliseconds)
        updateStatus("Ran Margin header/footer hooks on a failed scroll, with success recorded as a data field.")
    }

    fun runJsonSerializationScenario() = launchScenario("JSON serialization scroll demo") {
        val scroll = openScroll(appScribe, id = "json-serialization-1")

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
            tags = listOf("console", "serialization-test", "nested-object"),
            metadata = mapOf(
                "channel" to "stdout",
                "experiment" to "console-json-object",
            ),
        )

        scroll["demo_name"] = JsonPrimitive("json_serialization")
        scroll["order_snapshot"] = json.encodeToJsonElement(SerializationOrderSnapshot.serializer(), snapshot)
        scroll["order_id"] = JsonPrimitive(snapshot.orderId)
        scroll["buyer_tier"] = JsonPrimitive(snapshot.buyer.tier)
        scroll["primary_sku"] = JsonPrimitive(snapshot.lineItems.first().sku)
        scroll["channel"] = JsonPrimitive(snapshot.metadata["channel"] ?: "unknown")
        scroll["order_item_count"] = JsonPrimitive(snapshot.lineItems.sumOf { it.quantity })
        scroll["order_tag_count"] = JsonPrimitive(snapshot.tags.size)
        scroll["expected_render_checks"] = JsonPrimitive(
            "order_snapshot.order_id,order_snapshot.buyer.tier,order_snapshot.line_items[0].sku,order_snapshot.metadata.channel,order_id,buyer_tier,primary_sku,channel,order_item_count,order_tag_count",
        )

        sealScroll(scroll, appScribe)
        updateStatus("Ran JSON serialization demo with a nested object payload for console inspection.")
    }

    fun runEntrySaverScenario() = launchScenario("Unified EntrySaver demo") {
        emitQuickScroll(
            tag = "auth",
            message = "Session accepted for staff dashboard",
            level = "INFO",
        )
        val scroll = openScroll(appScribe, id = "session-audit")
        scroll["demo_name"] = JsonPrimitive("entry_saver_demo")
        scroll["role"] = JsonPrimitive("support")
        scroll["elevated_access"] = JsonPrimitive(true)
        sealScroll(scroll, appScribe)
        updateStatus("Ran two scrolls through one EntrySaver path.")
    }

    fun runOverflowScenario() = launchScenario("Overflow demo") {
        val baseline = printedEvents
        val attempted = 12

        appScribe.overflowDelay = true
        repeat(attempted) { index ->
            emitQuickScroll(
                tag = "buffer",
                message = "burst event #$index",
                level = if (index % 3 == 0) "WARN" else "INFO",
            )
        }
        delay(1800.milliseconds)
        appScribe.overflowDelay = false

        val delivered = printedEvents - baseline
        appendTimeline(
            title = "Overflow result",
            detail = "Attempted $attempted quick scrolls with channel capacity 2 and DROP_OLDEST; delivered $delivered.",
            payload = "",
            success = delivered < attempted,
        )
        updateStatus("Ran overflow demo with Channel(..., onBufferOverflow = DROP_OLDEST).")
    }

    fun runSaverFailureScenario() = launchScenario("Saver error demo") {
        emitQuickScroll(
            tag = "saver_failure",
            message = "Intentional saver failure probe",
            level = "WARN",
        )
        updateStatus("Saver failure demo ran; onSaver callback captures the injected failure.")
    }

    fun runRetireScenario() = launchScenario("retire() demo") {
        emitQuickScroll("shutdown", "retire() with light queue", "INFO")
        val started = currentEpochMillis()
        appScribe.retire()
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
        updateStatus("The shared demo Scribe is retired. Press Re-hire Scribe before sending more messages.")
    }

    fun runPlanRetireScenario() = launchScenario("retire() with backlog demo") {
        repeat(6) { index ->
            emitQuickScroll("shutdown", "drain probe #$index", "INFO")
        }
        val started = currentEpochMillis()
        appScribe.retire()
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
        updateStatus("The shared demo Scribe is retired after draining queued work. Press Re-hire Scribe to continue.")
    }

    fun wireIgnitionScenario() = launchScenario("onIgnition wiring") {
        emitQuickScroll(
            tag = "ignition",
            message = "onIgnition callback is configured; the demo avoids firing an uncaught exception.",
            level = "INFO",
        )
        _state.update {
            it.copy(
                ignitionMessage = "onIgnition is configured in this demo build. Triggering it live would terminate the app, so the showcase documents the hook instead of crashing itself.",
            )
        }
        updateStatus("Configured onIgnition safely without terminating the showcase process.")
    }

    fun rehireMainScribe() = launchScenario("Re-hire Scribe") {
        if (!_state.value.isRetired) {
            updateStatus("The shared demo Scribe is already active.")
            appendTimeline(
                title = "Re-hire Scribe",
                detail = "The shared demo Scribe was already active, so no recreation was needed.",
                payload = "",
                success = true,
            )
            return@launchScenario
        }

        appScribe.hire(
            channel = Channel(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST),
            scope = scope,
            onSaver = { saver, entry, error ->
                appendSaverError(
                    "Saver failure in ${saver::class.simpleName ?: "Saver"} for ${entryKind()}: ${error.message ?: error}",
                )
            },
        )
        _state.update { it.copy(isRetired = false) }
        refreshActiveScrolls()
        updateStatus("The shared demo Scribe was re-hired and can send messages again.")
        appendTimeline(
            title = "Re-hire Scribe",
            detail = "The shared demo Scribe object was hired again after retirement.",
            payload = "",
            success = true,
        )
    }

    fun close() {
        appScribe.close(scope)
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

    private fun handleRecord(entry: ScrollEntry) {
        val record = consoleRecordFromEntry(
            entry = entry,
            demoName = "shared_session",
            platform = platform,
            saverType = "EntrySaver",
            appVersion = appVersion,
            recordedAt = currentEpochMillis(),
        )

        printedEvents += 1
        val payload = json.encodeToString(JsonObject.serializer(), JsonObject(record))
        println(payload)
        _state.update {
            it.copy(
                lastRecord = payload,
                outputMessage = "Printed ${payloadEventKind(record)} record to the console.",
            )
        }
        appendTimeline(
            title = "${payloadEventKind(record)} via EntrySaver",
            detail = "${recordSummary(record)}. Printed to console.",
            payload = payload,
            success = true,
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
        println(message)
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

    private fun sealScroll(scroll: Scroll, scribe: Scribe) {
        scroll.seal(scribe)
        activeScrolls.remove(scroll.id)
        refreshActiveScrolls()
    }

    private fun entryKind(): String = "scroll"

    private fun emitQuickScroll(tag: String, message: String, level: String) {
        val scroll = appScribe.newScroll()
        scroll["demo_name"] = JsonPrimitive("quick_scroll")
        scroll["tag"] = JsonPrimitive(tag)
        scroll["message"] = JsonPrimitive(message)
        scroll["level"] = JsonPrimitive(level)
        scroll.seal(appScribe)
    }
}
