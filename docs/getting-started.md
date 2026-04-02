# Getting Started

## Add Scribe to `commonMain`

Use the library from shared code in your Kotlin Multiplatform module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:scribe:0.1.0")
        }
    }
}
```

## Create a Minimal `Scribe`

The smallest setup is a single saver. A `NoteSaver` only receives `Note` events:

```kotlin
Scribe.init {
    shelves = listOf(NoteSaver { note ->
        println("[${note.level}] ${note.tag}: ${note.message}")
    })
}
Scribe.hire()
```

## Emit a Single Event

Use `note(...)` when you can suspend and want delivery through the internal queue:

```kotlin
Scribe.note(
    tag = "payments",
    message = "starting checkout",
    level = Urgency.INFO,
)
```

If you need best-effort fire-and-forget behavior, use `flingNote(...)`:

```kotlin
val accepted = Scribe.flingNote(
    tag = "payments",
    message = "checkout queued",
    level = Urgency.DEBUG,
)

if (!accepted) {
    println("Note was rejected by the queue")
}
```

With the saver above, the log output looks like this:

```text
[INFO] payments: starting checkout
```

## Track a Flow with `Scroll`

`Scroll` is for multi-step operations where context accumulates before a final result is emitted.

```kotlin
Scribe.init {
    shelves = listOf(ScrollSaver { scroll ->
        println(scroll)
    })
    imprint = mapOf(
        "service" to JsonPrimitive("billing"),
        "environment" to JsonPrimitive("production"),
    )
}
Scribe.hire()

val scroll = Scribe.unrollScroll(id = "checkout-42")
scroll.writeString("gateway", "stripe")
scroll.writeNumber("attempt", 1)
scroll.writeBoolean("retry", false)
scroll.seal(success = true)
```

The emitted `SealedScroll` would look like this in JSON:

```json
{
  "scrollId": "checkout-42",
  "success": true,
  "errorMessage": null,
  "context": {
    "service": "billing",
    "environment": "production"
  },
  "data": {
    "gateway": "stripe",
    "attempt": 1,
    "retry": false
  }
}
```

Then in the saver you can manage this output whatever you want to save to you database or send to the cloud.

## Choose the Right Saver

```kotlin
val noteSaver = NoteSaver { note -> println(note) }
val scrollSaver = ScrollSaver { scroll -> println(scroll) }
val entrySaver = EntrySaver { entry -> println(entry) }
```

- `NoteSaver` handles only `Note`
- `ScrollSaver` handles only `SealedScroll`
- `EntrySaver` handles both

## What to Read Next

- [API Concepts](api-concepts.md) for the core types and terminology
- [Lifecycle and Delivery](lifecycle-and-delivery.md) for buffering, margins, shutdown, and error handling
