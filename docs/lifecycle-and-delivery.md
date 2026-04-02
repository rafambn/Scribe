# Lifecycle and Delivery

## Delivery Pipeline

`Scribe` sends entries through an internal `Channel` before invoking savers. This gives you one place to tune buffering and overflow behavior.

```kotlin
Scribe.init {
    shelves = listOf(EntrySaver { entry ->
        println(entry)
    })
}
Scribe.hire(
    deliveryConfig = ScribeDeliveryConfig(
        bufferSize = 256,
        overflowStrategy = BufferOverflow.DROP_OLDEST,
        onSaverError = { saver, entry, error ->
            println("Saver $saver failed for $entry: ${error.message}")
        }
    ),
)
```

## Suspending vs Best-Effort APIs

Choose based on backpressure and call-site constraints:

- `note(...)` suspends while sending to the queue
- `flingNote(...)` is best-effort, does not suspend, and returns `false` when the queue rejects the entry
- `seal(...)` suspends before dispatching the final `SealedScroll`
- `looseSeal(...)` is best-effort and does not suspend

## Shared Context with `imprint`

`imprint` adds fields to every new `Scroll` created by the same `Scribe`.

```kotlin
Scribe.init {
    shelves = listOf(ScrollSaver { println(it) })
    imprint = mapOf(
        "app" to JsonPrimitive("checkout"),
        "region" to JsonPrimitive("us-east-1"),
    )
}
Scribe.hire()
```

These values become part of the sealed scroll `context`.

## Open and Close Hooks with `Margin`

Use `Margin` when scrolls need standard fields at creation and sealing time.

```kotlin
val timingMargin = object : Margin {
    override fun header(scroll: Scroll) {
        scroll.writeNumber("startedAtEpochMs", 1000L)
    }

    override fun footer(scroll: Scroll) {
        scroll.writeNumber("sealedAtEpochMs", 2000L)
    }
}

Scribe.init {
    shelves = listOf(ScrollSaver { println(it) })
    margins = timingMargin
}
Scribe.hire()
```

## Graceful Shutdown

There are two shutdown modes:

- `retire()` closes the queue immediately
- `planRetire()` closes the queue and waits for queued work to finish

Use `planRetire()` when shutdown correctness matters more than speed.

## Uncaught Exceptions

Pass `onIgnition` to install the platform uncaught exception hook:

```kotlin
Scribe.init {
    shelves = listOf(EntrySaver { println(it) })
}
Scribe.hire(
    onIgnition = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    },
)
```
