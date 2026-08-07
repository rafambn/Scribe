# API Concepts

## Core Types

Scribe models logging with typed entries:

- `Scroll`: a mutable JSON-map you build up and then pass to `seal(...)`
- `Entry`: the open base interface for every payload sent through a runtime
- `ScrollEntry`: the immutable map snapshot produced by sealing a `Scroll`

## Terminology

- `newScroll(...)`: starts a contextual logging session
- `seal(scribe)`: applies the supplied runtime's footer, snapshots the
  current scroll data, and emits a `ScrollEntry`
- `extend(scroll)`: copies missing keys from another scroll into this one
- `append(key, scroll)`: nests a scroll as a JSON object under the given key
- `Margin`: hook for writing fields at open/close boundaries
- `hire(channel = ..., scope = ..., onSaver = ...)`: starts delivery over your channel configuration

## `Scribe`

`Scribe` is an abstract runtime base class. A user creates one or more objects
that extend it. Each object owns:

- one or more savers (`shelves`)
- an optional shared `imprint`
- optional lifecycle hooks through `Margin`
- optional uncaught exception wiring through `onIgnition` (the installed
  platform hook itself is global)

Define runtime configuration with overridden properties:

```kotlin
object CheckoutScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(entrySaver)
    override val imprint = mapOf("service" to JsonPrimitive("checkout"))
    override val margins = timingMargin
}
```

Delivery is started with `CheckoutScribe.hire(...)` and stopped with
`CheckoutScribe.retire()`. Different objects can run concurrently without
sharing queues, savers, or lifecycle.

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

Calling `seal(...)` more than once is allowed. Each call emits a separate
`ScrollEntry` through the `Scribe` passed to that call, with a snapshot of the data
at that point.

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

Configure queue behavior through the `Channel<Entry>` passed to an instance's
`hire(...)`.

```kotlin
CheckoutScribe.hire(
    channel = Channel(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ),
    onSaver = { saver, entry, error ->
        println("Saver $saver failed for $entry: $error")
    },
)
```

You can optionally provide a custom `CoroutineScope` to control the lifecycle of the delivery coroutine:

```kotlin
val customScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

CheckoutScribe.hire(
    scope = customScope,
    channel = Channel(capacity = 256),
)
```

## Event Shapes

The standard delivered event is a sealed `Scroll` snapshot. Fields written to
the scroll via normal map operations appear directly in the delivered `ScrollEntry`:

```kotlin
ScrollEntry(
    mapOf(
        "scroll_id" to JsonPrimitive("checkout-42"),
        "gateway" to JsonPrimitive("stripe"),
    ),
)
```

## Failure Handling

```kotlin
object ApplicationScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(entrySaver)
    override val onIgnition: ((Throwable) -> Unit)? = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
}

ApplicationScribe.hire(
    channel = Channel(capacity = 256),
    onSaver = { saver, entry, error ->
        println("Saver $saver failed for $entry: ${error.message}")
    },
)
```

`onIgnition` is read when that runtime is first hired, but handles uncaught
exceptions at the platform level. Multiple runtimes should not independently
claim this application-global hook. Saver failures are reported by the
`onSaver` callback passed to `hire(...)`.
