# Scribe

`Scribe` is a Kotlin Multiplatform logging library with a lore-driven API.

- A `Scribe` writes logs
- A `Note` is a single log line
- A `Scroll` collects context across a flow
- A `SealedScroll` is the final immutable result
- `Saver`s place notes and scrolls onto `shelves` such as console, database, or HTTP backends

## Terminology

- `note(...)`: suspend function for delivering a single log entry
- `flingNote(...)`: best-effort non-suspending variant
- `unrollScroll(...)`: starts a contextual log
- `seal(...)`: finalizes a scroll and emits a `SealedScroll`
- `looseSeal(...)`: best-effort non-suspending variant
- `Margin`: hook that can add fields when a scroll starts or seals
- `ScribeDeliveryConfig`: queue and overflow behavior for async delivery

## Installation

The library is not published yet. Add the `scribe` module directly or publish it from this repository.

## Create Notes

```kotlin
val scribe = Scribe(
    shelves = listOf(
        NoteSaver { note ->
            println("[${note.level}] ${note.tag}: ${note.message}")
        }
    )
)

scribe.note(
    tag = "payments",
    message = "starting checkout",
    level = Urgency.INFO,
)
```

## Create Scrolls

```kotlin
val scribe = Scribe(
    shelves = listOf(
        ScrollSaver { scroll ->
            println(scroll)
        }
    ),
    imprint = mapOf(
        "service" to JsonPrimitive("billing"),
        "environment" to JsonPrimitive("production"),
    )
)

val scroll = scribe.unrollScroll(id = "checkout-42")
scroll.writeString("gateway", "stripe")
scroll.writeNumber("attempt", 1)
scroll.writeBoolean("retry", false)
scroll.seal(success = true)
```

The emitted `SealedScroll` contains:

- `scrollId`
- `success`
- `errorMessage`
- shared `context`
- scroll-specific `data`

## Saver Types

Use the saver that matches the output you want:

```kotlin
val noteSaver = NoteSaver { note -> println(note) }
val scrollSaver = ScrollSaver { scroll -> println(scroll) }
val recordSaver = EntrySaver { record -> println(record) }
```

- `NoteSaver` receives only `Note`
- `ScrollSaver` receives only `SealedScroll`
- `EntrySaver` receives both

## Margins

`Margin` lets you enrich a scroll at the beginning and end of its lifecycle.

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

## Delivery

`Scribe` delivers records through an internal channel processed on a coroutine scope.

```kotlin
val scribe = Scribe(
    shelves = listOf(recordSaver),
    deliveryConfig = ScribeDeliveryConfig(
        bufferSize = 256,
        overflowStrategy = BufferOverflow.DROP_OLDEST,
        onSaverError = { saver, record, error ->
            println("Saver $saver failed for $record: $error")
        }
    )
)
```

Use `flingNote()` and `looseSeal()` when you want a non-suspending best-effort call.

## Lifecycle

`Scribe` defaults to its own internal `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. For lifecycle-bound environments (Android, server frameworks) pass your own scope so cancellation propagates correctly.

```kotlin
// Simple — uses the built-in scope
val scribe = Scribe(shelves = listOf(recordSaver))

// Lifecycle-bound — you control when the processor stops
object AppLog {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val scribe = Scribe(
        scope = appScope,
        shelves = listOf(recordSaver),
    )
}
```

Two shutdown options:

- `retire()` — non-suspending, closes the channel and returns immediately. The processor continues draining whatever is already buffered on a best-effort basis. Use this from non-coroutine contexts (e.g. `onDestroy`, shutdown hooks).
- `planRetire()` — suspending, closes the channel and waits until every buffered entry has been processed. Use this when you need a clean shutdown guarantee.

To stop the processor immediately, cancel the scope you passed to `Scribe`. The library will detect the cancellation and close the channel so subsequent sends fail visibly.

## Uncaught Exceptions

`Scribe` can install a platform uncaught exception hook through `onIgnition`.

```kotlin
val scribe = Scribe(
    shelves = listOf(recordSaver),
    onIgnition = { throwable ->
        println("Uncaught exception: ${throwable.message}")
    }
)
```

Use this when you want the logger to observe crashes that escape normal application flow.

## Status

This repository is still under active design. Backward compatibility is not guaranteed yet.
