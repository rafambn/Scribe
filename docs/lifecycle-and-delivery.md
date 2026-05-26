# Lifecycle and Delivery

## Delivery Pipeline

A `Scribe` object delivers entries through the `Channel<Entry>` provided to
`hire(...)`. The channel is disposable and transfers ownership to that object,
which closes it on processor completion or `retire()`. Different `Scribe`
objects may be hired concurrently with independent channels.

You can optionally provide a custom `CoroutineScope` to control the delivery coroutine lifecycle:

```kotlin
val customScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

CheckoutScribe.hire(
    scope = customScope,
    channel = Channel(capacity = 256),
)
```

```kotlin
object CheckoutScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(EntrySaver { entry ->
        println(entry)
    })
}

CheckoutScribe.hire(
    channel = Channel(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ),
    onSaver = { saver, entry, error ->
        println("Saver $saver failed for $entry: ${error.message}")
    },
)
```

## Emission APIs

Current emission calls are non-suspending:

- `note(...)` sends a `Note`
- `seal(scribe, ...)` applies that runtime's footer margin, snapshots the
  current `Scroll` data, and sends a `SealedScroll`

Both calls attempt an immediate channel send and block the calling thread if a
channel configured with `BufferOverflow.SUSPEND` is full. `Saver.write(...)`
and `retire()` are the suspending parts of the API. There are no separate
best-effort emission APIs in this runtime shape.

Multiple calls to `seal(...)` on the same `Scroll` are intentional. Each call
emits a separate `SealedScroll` through the `Scribe` passed to that call.

## Shared Context with `imprint`

`imprint` adds fields to every new `Scroll` created by the same `Scribe` object.

```kotlin
object CheckoutScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(ScrollSaver { println(it) })
    override val imprint = mapOf(
        "app" to JsonPrimitive("checkout"),
        "region" to JsonPrimitive("us-east-1"),
    )
}

CheckoutScribe.hire(channel = Channel(capacity = 256))
```

These values are inserted into the scroll map and then appear in `SealedScroll.data`.

## Open and Close Hooks with `Margin`

Use `Margin` when scrolls need standard fields at creation and sealing time.

```kotlin
val timingMargin = object : Margin {
    override fun header(scroll: Scroll) {
        scroll["started_at"] = JsonPrimitive(1000)
    }

    override fun footer(scroll: Scroll) {
        scroll["sealed_at"] = JsonPrimitive(2000)
    }
}

object CheckoutScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(ScrollSaver { println(it) })
    override val margins = timingMargin
}

CheckoutScribe.hire(channel = Channel(capacity = 256))
```

## Graceful Shutdown

Use `retire()` to stop intake and wait until queued delivery work is finished.

```kotlin
CheckoutScribe.retire()
```

After `retire()`, that object's previous channel is closed and cannot be
reused. Call `hire(...)` with a new channel to restart its delivery. Other
active `Scribe` objects are unaffected.

## Uncaught Exceptions

Override `onIgnition` on an application-owned `Scribe` object to install the
platform uncaught exception hook when that object is first hired:

```kotlin
object ApplicationScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(EntrySaver { println(it) })
    override val onIgnition: ((Throwable) -> Unit)? = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
}
```

This hook is platform-global even though the property is declared by one
runtime object. Saver-level failures are handled separately by `onSaver` passed
to `hire(...)`.
