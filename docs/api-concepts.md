# API Concepts

## Core Types

Scribe models logging with two event shapes:

- `Note`: a single standalone event
- `SealedScroll`: a sealed snapshot result of a multi-step `Scroll`

Both implement the sealed `Entry` interface, which is what `EntrySaver` receives.

## Terminology

- `note(...)`: suspending call for a single log entry
- `newScroll(...)`: starts a contextual logging session
- `seal(...)`: snapshots the current scroll data and emits a `SealedScroll`
- `extend(scroll)`: copies missing keys from another scroll into this one
- `append(key, scroll)`: nests a scroll as a JSON object under the given key
- `Margin`: hook for writing fields at open/close boundaries
- `hire(channel = ..., scope = ..., onSaver = ...)`: starts delivery over your channel configuration

## `Scribe`

`Scribe` is the process-wide singleton entry point. It owns:

- one or more savers (`shelves`)
- an optional shared `imprint`
- optional lifecycle hooks through `Margin`
- optional uncaught exception wiring through `onIgnition`

Initialization is done once with `Scribe.inscribe { ... }`.

Delivery is started with `Scribe.hire(...)` and stopped with `retire()`.

## `Scroll`

`Scroll` is a typealias:

```kotlin
typealias Scroll = MutableMap<String, JsonElement>
```

You write JSON-safe values directly into the map.

```kotlin
val scroll = Scribe.newScroll(id = "checkout-42")
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
val scroll = Scribe.newScroll(id = "checkout-42")
println(scroll.id) // "checkout-42"
```

Calling `seal(...)` more than once is allowed. Each call emits a separate `SealedScroll` with the current `success` value and a snapshot of the data at that point.

## `Scroll` Extensions

Beyond direct map writes, `Scroll` has two extension functions:

### `extend(scroll)`
Copies only missing keys from another scroll into this one:

```kotlin
val base = Scribe.newScroll(id = "base")
base["gateway"] = JsonPrimitive("stripe")

val checkout = Scribe.newScroll(id = "checkout-42")
checkout["attempt"] = JsonPrimitive(1)
checkout.extend(base) // only copies "gateway" if not already present
```

### `append(key, scroll)`
Nests another scroll as a `JsonObject` under the given key:

```kotlin
val meta = mutableMapOf<String, JsonElement>()
meta["item_count"] = JsonPrimitive(3)
checkout.append("cart", meta)
// Result: checkout["cart"] = {"item_count": 3}
```

## `Margin`

`Margin` enriches a scroll at beginning and end.

```kotlin
val timingMargin = object : Margin {
    override fun header(scroll: Scroll) {
        scroll["started_at"] = JsonPrimitive(1000)
    }

    override fun footer(scroll: Scroll) {
        scroll["sealed_at"] = JsonPrimitive(2000)
    }
}
```

## Delivery Configuration

`Scribe` no longer accepts a dedicated delivery config object. You configure queue behavior through the `Channel<Entry>` you pass to `hire(...)`.

```kotlin
Scribe.inscribe {
    shelves = listOf(entrySaver)
}

Scribe.hire(
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

Scribe.hire(
    scope = customScope,
    channel = Channel(capacity = 256),
)
```

## Event Shapes

```kotlin
Note(
    tag = "payments",
    message = "starting checkout",
    level = Urgency.INFO,
    timestamp = 1710000000000L,
)
```

```kotlin
SealedScroll(
    success = true,
    data = mapOf(
        "scroll_id" to JsonPrimitive("checkout-42"),
        "gateway" to JsonPrimitive("stripe"),
    ),
)
```

## Urgency Levels

`Urgency` is used by `Note` to indicate severity:

```kotlin
enum class Urgency {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
```

## Failure Handling

```kotlin
Scribe.inscribe {
    shelves = listOf(entrySaver)
    onIgnition = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
}

Scribe.hire(
    channel = Channel(capacity = 256),
    onSaver = { saver, entry, error ->
        println("Saver $saver failed for $entry: ${error.message}")
    },
)
```

`onIgnition` handles uncaught exceptions at the platform level. Saver failures are reported by the `onSaver` callback passed to `hire(...)`.
