# Scribe

<div class="hero" markdown>

<div align="center">
  <img src="scribe-logo.svg" alt="Scribe Logo" width="180"/>
</div>

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg)](https://kotlinlang.org/docs/multiplatform.html)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.rafambn/scribe)](https://search.maven.org/search?q=g:io.github.rafambn%20AND%20a:scribe)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-orange.svg)](https://opensource.org/license/apache-2-0)

**[Project Repository](https://github.com/rafambn/Scribe)**

A Kotlin Multiplatform logging library with a lore-driven API.

</div>

## ✨ Features

- 🧾 **Story-Driven Primitives** - `Scribe`, `Scroll`, and `SealedScroll` model logging as narrative flow
- 🌍 **Multiplatform** - Kotlin Multiplatform-first design with shared logging contracts
- ⚙️ **Flexible Delivery** - tune channel buffering and overflow behavior with `ScribeDeliveryConfig`
- 🪝 **Lifecycle Hooks** - enrich and finalize contextual logs through `Margin`
- 🚀 **Suspending + Best-Effort APIs** - pick strict (`note`, `seal`) or non-blocking (`flingNote`, `looseSeal`) calls

## 📦 Installation

Add dependency in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.rafambn:scribe:1.0.0")
}
```

## 🚀 Quick Start

### Create Notes

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

### Create Scrolls

```kotlin
val scribe = Scribe(
    shelves = listOf(
        ScrollSaver { scroll -> println(scroll) }
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

## 🎯 Delivery Model

Use the saver type that matches your output flow:

```kotlin
val noteSaver = NoteSaver { note -> println(note) }
val scrollSaver = ScrollSaver { scroll -> println(scroll) }
val entrySaver = EntrySaver { record -> println(record) }
```

- `NoteSaver` receives only `Note`
- `ScrollSaver` receives only `SealedScroll`
- `EntrySaver` receives both

## 🔧 Lifecycle and Shutdown

- `retire()` closes the channel immediately and drains buffered entries best-effort.
- `planRetire()` suspends until buffered entries are fully processed.
- If you pass your own scope, cancellation propagates and stops processing in a controlled way.

## 📚 Next

For terminology and configuration details, see [API Concepts](api-concepts.md).
