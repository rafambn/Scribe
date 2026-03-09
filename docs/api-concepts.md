# API Concepts

## Terminology

- `note(...)`: suspending call for a single log entry
- `flingNote(...)`: non-suspending best-effort note dispatch
- `unrollScroll(...)`: starts a contextual logging session
- `seal(...)`: finalizes a scroll and emits a `SealedScroll`
- `looseSeal(...)`: non-suspending best-effort seal
- `Margin`: hook for writing fields at open/close boundaries
- `ScribeDeliveryConfig`: queue size, overflow strategy, and saver error handling

## Margins

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
    shelves = listOf(entrySaver),
    deliveryConfig = ScribeDeliveryConfig(
        bufferSize = 256,
        overflowStrategy = BufferOverflow.DROP_OLDEST,
        onSaverError = { saver, record, error ->
            println("Saver $saver failed for $record: $error")
        }
    )
)
```

## Uncaught Exceptions

```kotlin
val scribe = Scribe(
    shelves = listOf(entrySaver),
    onIgnition = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
)
```
