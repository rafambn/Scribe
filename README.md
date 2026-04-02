<h1 align="center">Scribe</h1>

<p align="center">A flavored Kotlin Multiplatform logging library</p>

<p align="center">
  <img src="scribe-logo.svg" alt="Scribe logo" width="180" />
</p>

<p align="center">
  <a href="https://search.maven.org/search?q=g:com.rafambn%20AND%20a:scribe">
    <img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.rafambn/scribe?label=Maven%20Central">
  </a>
  <a href="./LICENSE">
    <img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg">
  </a>
  <img alt="Platform Targets" src="https://img.shields.io/badge/targets-android%20%7C%20jvm%20%7C%20ios-0A7EA4">
</p>

<p align="center">
  Scribe is a Kotlin Multiplatform logging library built around the ideas from <a href="https://loggingsucks.com">loggingsucks.com</a>, so structured logs can model both single events and longer contextual flows.
</p>

<table align="center">
  <tr>
    <td align="center">
      <a href="https://scribe.rafambn.com/"><strong>Documentation Page</strong></a>
    </td>
  </tr>
</table>

## Features:

- Story-driven logging primitives instead of flat logger calls
- Single-event logging with `note(...)` and contextual logging with `unrollScroll(...)`
- Best-effort non-suspending variants with `flingNote(...)` (returns `Boolean` acceptance) and `looseSeal(...)`
- Delivery hooks through `NoteSaver`, `ScrollSaver`, and `EntrySaver`
- Scroll lifecycle enrichment through `Margin`

## Setup

Add Scribe to your `commonMain` dependencies:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:scribe:0.1.0")
        }
    }
}
```

## Usage

Initialize `Scribe`, hire the runtime, and emit a note:

```kotlin
Scribe.inscribe {
    shelves = listOf(
        NoteSaver { note ->
            println("[${note.level}] ${note.tag}: ${note.message}")
        }
    )
}
Scribe.hire()

Scribe.note(
    tag = "payments",
    message = "starting checkout",
    level = Urgency.INFO,
)
```

Use a scroll when you need shared context for a longer flow:

```kotlin
Scribe.inscribe {
    shelves = listOf(
        ScrollSaver { scroll -> println(scroll) }
    )
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

Choose the saver that matches your output flow:

```kotlin
val noteSaver = NoteSaver { note -> println(note) }
val scrollSaver = ScrollSaver { scroll -> println(scroll) }
val entrySaver = EntrySaver { record -> println(record) }
```
