# Getting Started

## Add Scribe to `commonMain`

Use the library from shared code in your Kotlin Multiplatform module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:scribe:0.5.0")
        }
    }
}
```

## Create a Minimal `Scribe`

Create an object that extends `Scribe`, configure its private buffer, then hire its processor.

```kotlin
object AppScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
    override val archivists: List<Archivist> = listOf(Archivist { scroll ->
        println(scroll)
    })
}

AppScribe.hire()
```

## Emit a Single Event

Every event is a scroll. For a standalone event, build a scroll and seal it
immediately:

```kotlin
val scroll = AppScribe.newScroll()
scroll["tag"] = JsonPrimitive("payments")
scroll["message"] = JsonPrimitive("starting checkout")
scroll["level"] = JsonPrimitive("INFO")
scroll.seal(AppScribe)
```

With the archivist above, the log output looks like this:

```text
{scroll_id=..., tag=payments, message=starting checkout, level=INFO}
```

## Track a Flow with `Scroll`

`Scroll` is a mutable map of JSON elements initialized by `newScroll(...)`.
When sealing it, supply the `Scribe` runtime that should apply its footer
margin and deliver the event. Each `seal(...)` call emits a new snapshot of
the scroll data at that moment.

You can also merge other scrolls or nest them:

```kotlin
val base = AppScribe.newScroll()
base["gateway"] = JsonPrimitive("stripe")

val checkout = AppScribe.newScroll(id = "checkout-42")
checkout.extend(base) // copies missing keys from base

val meta = AppScribe.newScroll(id = "checkout-meta")
meta["items"] = JsonPrimitive(3)
checkout.append("meta", meta)
```

```kotlin
val scroll = AppScribe.newScroll(id = "checkout-42")
scroll["gateway"] = JsonPrimitive("stripe")
scroll["attempt"] = JsonPrimitive(1)
scroll["retry"] = JsonPrimitive(false)
scroll["cart"] = Json.encodeToJsonElement(
    CheckoutMeta.serializer(),
    CheckoutMeta(itemCount = 3, subtotalCents = 249_900, featureFlag = "wide-events"),
)
scroll.seal(AppScribe)
```

## Use Multiple Runtimes

Each object is independent. A library may define its own object, or an
application may supply a configured object to a component.

```kotlin
object PaymentsScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
    override val archivists: List<Archivist> = listOf(Archivist { sendPaymentsRecord(it) })
}

object AnalyticsScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
    override val archivists: List<Archivist> = listOf(Archivist { sendAnalyticsRecord(it) })
}

PaymentsScribe.hire()
AnalyticsScribe.hire()
```

Dismissing `PaymentsScribe` pauses only its job and does not stop `AnalyticsScribe`.

The emitted event shape is the scroll map itself:

```json
{
  "scroll_id": "checkout-42",
  "gateway": "stripe",
  "attempt": 1,
  "retry": false,
  "cart": {
    "item_count": 3,
    "subtotal_cents": 249900,
    "feature_flag": "wide-events"
  }
}
```

## Choose the Right Archivist

```kotlin
val scrollArchivist = Archivist { scroll -> println(scroll) }
```

- Every archivist receives `Entry` snapshots
- `Archivist` is a functional interface: `Archivist { entry -> ... }` is all you need
- Add multiple savers to a `Scribe` object to fan out to several outputs

## What to Read Next

- [API Concepts](api-concepts.md) for the core types and terminology
- [Lifecycle and Delivery](lifecycle-and-delivery.md) for intake, processing, retirement, and archivist error callbacks
