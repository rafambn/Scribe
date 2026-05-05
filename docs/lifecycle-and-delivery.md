# Lifecycle and Delivery

## Delivery Pipeline

`Scribe` delivers entries through the `Channel<Entry>` you provide to `hire(...)`. The channel is disposable and transfers ownership to Scribe, which closes it on processor completion or `retire()`. Create a fresh channel for each `hire(...)` call.

```kotlin
Scribe.inscribe {
    shelves = listOf(EntrySaver { entry ->
        println(entry)
    })
}

Scribe.hire(
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

Current emission calls are suspending:

- `note(...)` sends a `Note`
- `seal(...)` snapshots the current `Scroll` data and sends a `SealedScroll`

There are no separate best-effort APIs in this runtime shape.

Multiple calls to `seal(...)` on the same `Scroll` are intentional. Each call emits a separate `SealedScroll`, so a flow can record more than one terminal snapshot when that is useful.

## Shared Context with `imprint`

`imprint` adds fields to every new `Scroll` created by the same `Scribe`.

```kotlin
Scribe.inscribe {
    shelves = listOf(ScrollSaver { println(it) })
    imprint = mapOf(
        "app" to JsonPrimitive("checkout"),
        "region" to JsonPrimitive("us-east-1"),
    )
}

Scribe.hire(channel = Channel(capacity = 256))
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

Scribe.inscribe {
    shelves = listOf(ScrollSaver { println(it) })
    margins = timingMargin
}

Scribe.hire(channel = Channel(capacity = 256))
```

## Graceful Shutdown

Use `retire()` to stop intake and wait until queued delivery work is finished.

```kotlin
Scribe.retire()
```

After `retire()`, the previous channel is closed and cannot be reused. Call `hire(...)` with a new channel to restart runtime delivery.

## Uncaught Exceptions

Set `onIgnition` in `Scribe.inscribe { ... }` to install the platform uncaught exception hook:

```kotlin
Scribe.inscribe {
    shelves = listOf(EntrySaver { println(it) })
    onIgnition = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
}
```

Saver-level failures are handled separately by `onSaver` passed to `hire(...)`.
