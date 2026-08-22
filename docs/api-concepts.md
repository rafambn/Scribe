# API Concepts

## Core Types

Scribe models logging with structured scroll events:

- `Scroll`: a mutable JSON-map you build up and then pass to `seal(...)`
- `Entry`: typealias for `Map<String, JsonElement>`, the read-only snapshot produced by sealing a `Scroll` and delivered through a runtime's archivists

## Terminology

- `newScroll(...)`: starts a contextual logging session
- `seal(scribe)`: applies the supplied runtime's footer, snapshots the
  current scroll data, attempts a non-blocking enqueue, and returns the `Entry`
- `extend(scroll)`: copies missing keys from another scroll into this one
- `append(key, scroll)`: nests a scroll as a JSON object under the given key
- `Margin`: hook for writing fields at open/close boundaries
- `hire()`: starts or resumes processing of the private buffer
- `openIntake()` / `closeIntake()`: independently control whether new entries are accepted
- `dismiss()`: requests a cooperative job pause while preserving buffered entries
- `retire()`: permanently closes intake and drains the buffer

## `Scribe`

`Scribe` is an abstract runtime base class. A user creates one or more objects
that extend it. Each object owns:

- zero or more configured archivists (at least one is required when `hire()` is called)
- a private buffer with a capacity and overflow policy
- an optional shared `imprint`
- optional lifecycle hooks through `Margin`
- optional uncaught exception wiring through `onIgnition` (the installed
  platform hook itself is global)

Define runtime configuration with overridden properties:

```kotlin
object CheckoutScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
    override val archivists: List<Archivist> = listOf(Archivist { entry -> println(entry) })
    override val imprint = mapOf("service" to JsonPrimitive("checkout"))
    override val margins = timingMargin
}
```

Intake starts open and the job starts dismissed. Delivery is started with
`CheckoutScribe.hire()`, paused with `CheckoutScribe.dismiss()`, and permanently
ended with `CheckoutScribe.retire()`. Different objects have independent private
buffers, archivists, and lifecycle controls.

## `Scroll`

`Scroll` is a typealias for `MutableMap<String, JsonElement>`. Calling
`newScroll(...)` initializes it with the ID, imprint, and header margin from
that `Scribe`, but the map does not retain a runtime reference. Supply the
runtime that should apply its footer and deliver the snapshot to `seal(...)`:

```kotlin
val scroll: Scroll = CheckoutScribe.newScroll(id = "checkout-42")
scroll["gateway"] = JsonPrimitive("stripe")
scroll.seal(CheckoutScribe) // applies/delivers through CheckoutScribe
```

It delegates normal mutable map operations, so you write JSON-safe values
directly into it.

```kotlin
val scroll = CheckoutScribe.newScroll(id = "checkout-42")
scroll["gateway"] = JsonPrimitive("stripe")
scroll["attempt"] = JsonPrimitive(1)
scroll["retry"] = JsonPrimitive(false)
```

You can read/remove fields with normal map operations:

```kotlin
val phase = scroll["phase"]
val removed = scroll.remove("retryable")
```

`scroll.id` reads the generated/custom `scroll_id` field:

```kotlin
val scroll = CheckoutScribe.newScroll(id = "checkout-42")
println(scroll.id) // "checkout-42"
```

Calling `seal(...)` more than once is allowed. Each call applies the footer again, creates and
returns a separate `Entry` snapshot, and attempts delivery through the supplied `Scribe`.

## `Scroll` Operations

Beyond direct map writes, `Scroll` has two convenience operations:

### `extend(scroll)`
Copies only missing keys from another scroll into this one:

```kotlin
val base = CheckoutScribe.newScroll(id = "base")
base["gateway"] = JsonPrimitive("stripe")

val checkout = CheckoutScribe.newScroll(id = "checkout-42")
checkout["attempt"] = JsonPrimitive(1)
checkout.extend(base) // only copies "gateway" if not already present
```

### `append(key, scroll)`
Nests another scroll as a `JsonObject` under the given key:

```kotlin
val meta = CheckoutScribe.newScroll(id = "cart-meta")
meta["item_count"] = JsonPrimitive(3)
checkout.append("cart", meta)
// Result: checkout["cart"] = {"item_count": 3}
```

## `Margin`

`Margin` enriches a scroll at beginning and end.

```kotlin
val margin = object : Margin {
    override fun header(scroll: Scroll) {
        scroll["started_at"] = JsonPrimitive(1000)
    }

    override fun footer(scroll: Scroll) {
        scroll["sealed_at"] = JsonPrimitive(2000)
    }
}
```

## Delivery Configuration

Configure private-buffer behavior when creating the `Scribe`.

```kotlin
object CheckoutScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure = { archivist: Archivist, entry: Entry, error: Throwable ->
        println("Archivist $archivist failed for $entry: $error")
    }
    override val archivists = listOf(Archivist { entry -> println(entry) })
}

CheckoutScribe.hire()
```

The delivery coroutine is owned by the `Scribe` instance so it can remain alive while processing
is paused and resume on a later `hire()`. The configuration properties have defaults; only
`archivists` normally needs to be overridden for a minimal implementation.

## Event Shapes

The standard delivered event is a sealed `Scroll` snapshot. Fields written to
the scroll via normal map operations appear directly in the delivered `Entry`,
which is a `Map<String, JsonElement>`:

```kotlin
mapOf(
    "scroll_id" to JsonPrimitive("checkout-42"),
    "gateway" to JsonPrimitive("stripe"),
)
```

## Failure Handling

```kotlin
object ApplicationScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure = { archivist: Archivist, entry: Entry, error: Throwable ->
        println("Archivist $archivist failed for $entry: ${error.message}")
    }
    override val archivists: List<Archivist> = listOf(Archivist { entry -> println(entry) })
    override val onIgnition: ((Throwable) -> Unit)? = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
}

ApplicationScribe.hire()
```

`onIgnition` is registered on the first `hire()` and unregistered by `retire()`.
Scribe keeps one internal platform dispatcher and fans each failure out once to
every active callback; a callback failure does not prevent the remaining
callbacks or the runtime's previous handler from observing it. Browser JS and
wasmJs observe global errors and unhandled Promise rejections. Node uses only
its uncaught-exception monitor, distinguishing `uncaughtException` from
`unhandledRejection` through the monitor's `origin` argument, so it does not
change Node's normal termination behavior. An unhandled rejection reaches the
monitor only when the host's current `--unhandled-rejections` policy promotes
it. wasmWasi has no portable global hook, so `hire()` rejects a non-null
`onIgnition` with an explicit unsupported-operation error. Archivist failures are reported by the
`onArchiveFailure` property defined by the implementation.
