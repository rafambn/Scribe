# Getting Started

## Add Scribe to `commonMain`

Use the library from shared code in your Kotlin Multiplatform module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:scribe:0.2.3")
        }
    }
}
```

## Create a Minimal `Scribe`

Initialize once with one or more savers, then hire the runtime with a `Channel<Entry>`.

```kotlin
Scribe.inscribe {
    shelves = listOf(NoteSaver { note ->
        println("[${note.level}] ${note.tag}: ${note.message}")
    })
}

Scribe.hire(
    channel = Channel(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ),
)
```

## Emit a Single Event

Use `note(...)` for standalone events:

```kotlin
Scribe.note(
    tag = "payments",
    message = "starting checkout",
    level = Urgency.INFO,
)
```

With the saver above, the log output looks like this:

```text
[INFO] payments: starting checkout
```

## Track a Flow with `Scroll`

`Scroll` is a mutable map (`MutableMap<String, JsonElement>`) that you seal into one wide event.
Each `seal(...)` call emits a new `SealedScroll` using a snapshot of the scroll data at that moment.

```kotlin
val scroll = Scribe.newScroll(id = "checkout-42")
scroll["gateway"] = JsonPrimitive("stripe")
scroll["attempt"] = JsonPrimitive(1)
scroll["retry"] = JsonPrimitive(false)
scroll["cart"] = Json.encodeToJsonElement(
    CheckoutMeta.serializer(),
    CheckoutMeta(itemCount = 3, subtotalCents = 249_900, featureFlag = "wide-events"),
)
scroll.seal(success = true)
```

The emitted `SealedScroll` shape:

```json
{
  "success": true,
  "data": {
    "scroll_id": "checkout-42",
    "gateway": "stripe",
    "attempt": 1,
    "retry": false,
    "cart": {
      "item_count": 3,
      "subtotal_cents": 249900,
      "feature_flag": "wide-events"
    }
  }
}
```

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
- [Lifecycle and Delivery](lifecycle-and-delivery.md) for channel behavior, margins, shutdown, and saver error callbacks
