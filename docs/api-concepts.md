# API Concepts

## Core Types

Scribe models logging with two event shapes:

- `Note`: a single standalone event
- `SealedScroll`: the finalized result of a multi-step `Scroll`

Both implement the sealed `Entry` interface, which is what `EntrySaver` receives.

## Terminology

- `note(...)`: suspending call for a single log entry
- `flingNote(...)`: non-suspending best-effort note dispatch
- `unrollScroll(...)`: starts a contextual logging session
- `seal(...)`: finalizes a scroll and emits a `SealedScroll`
- `looseSeal(...)`: non-suspending best-effort seal
- `Margin`: hook for writing fields at open/close boundaries
- `ScribeDeliveryConfig`: queue size, overflow strategy, and saver error handling

## `Scribe`

`Scribe` is the entry point. It owns:

- one or more savers (`shelf` or `shelves`)
- an optional shared `imprint`
- delivery behavior through `ScribeDeliveryConfig`
- optional lifecycle hooks through `Margin`

You can inspect active scrolls with `seekScrolls()` and create a new one with `unrollScroll()`.

## `Scroll`

`Scroll` collects structured fields until it is sealed.

```kotlin
val scroll = scribe.unrollScroll("checkout-42")
scroll.writeString("gateway", "stripe")
scroll.writeNumber("attempt", 1)
scroll.writeBoolean("retry", false)
```

The API is intentionally explicit:

- `writeString(...)`
- `writeNumber(...)`
- `writeBoolean(...)`
- `writeSerializable(...)`
- `read(...)`
- `erase(...)`

After a scroll is sealed, writes and erases stop mutating it.

## `Margin`

`Margin` enriches a scroll at beginning and end.

```kotlin
val timingMargin = object : Margin {
    override fun header(scroll: Scroll) {
        scroll.writeNumber("startedAtEpochMs", 1000L)
    }

    override fun footer(scroll: Scroll) {
        scroll.writeNumber("sealedAtEpochMs", 2000L)
    }
}
```

## Delivery Configuration

```kotlin
val scribe = Scribe(
    shelf = entrySaver,
    deliveryConfig = ScribeDeliveryConfig(
        bufferSize = 256,
        overflowStrategy = BufferOverflow.DROP_OLDEST,
        onSaverError = { saver, entry, error ->
            println("Saver $saver failed for $entry: $error")
        }
    )
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
    scrollId = "checkout-42",
    success = true,
    errorMessage = null,
    context = mapOf(),
    data = mapOf(),
)
```

## Failure Handling

```kotlin
val scribe = Scribe(
    shelf = entrySaver,
    onIgnition = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
)
```

`onIgnition` handles uncaught exceptions at the platform level. Saver failures are handled separately through `ScribeDeliveryConfig.onSaverError`.
