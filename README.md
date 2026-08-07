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
  <img alt="Platform Targets" src="https://img.shields.io/badge/targets-android%20%7C%20jvm%20%7C%20ios%20%7C%20linuxX64%20%7C%20mingwX64%20%7C%20macosArm64-0A7EA4">
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
- Contextual logging with `newScroll(...)` and immediate-seal one-shot scrolls
- Delivery hooks through typed `Saver<T>` instances and `EntrySaver`
- Scroll lifecycle enrichment through `Margin`
- Independent `Scribe` objects for applications and imported libraries

## Setup

Add Scribe to your `commonMain` dependencies:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:scribe:0.5.0")
        }
    }
}
```

## Usage

Create a `Scribe` object, hire its runtime, and emit a scroll:

```kotlin
object AppScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(
        Saver<ScrollEntry> { scroll ->
            println(scroll)
        }
    )
}
AppScribe.hire(channel = Channel(capacity = 256))

val scroll = AppScribe.newScroll()
scroll["tag"] = JsonPrimitive("payments")
scroll["message"] = JsonPrimitive("starting checkout")
scroll["level"] = JsonPrimitive("INFO")
scroll.seal(AppScribe)
```

Use a scroll when you need shared context for a longer flow:

```kotlin
object BillingScribe : Scribe() {
    override val shelves: List<Saver<*>> = listOf(
        Saver<ScrollEntry> { scroll -> println(scroll) }
    )
    override val imprint = mapOf(
        "service" to JsonPrimitive("billing"),
        "environment" to JsonPrimitive("production"),
    )
}
BillingScribe.hire(channel = Channel(capacity = 256))

val scroll = BillingScribe.newScroll(id = "checkout-42")
scroll["gateway"] = JsonPrimitive("stripe")
scroll["attempt"] = JsonPrimitive(1)
scroll["retry"] = JsonPrimitive(false)
scroll.seal(BillingScribe)
```

Each `Scribe` object has independent configuration and delivery lifecycle. A `Scroll` is a mutable JSON-element map initialized by `newScroll(...)`; pass the runtime that should enrich and deliver it to `scroll.seal(scribe)`. Each `seal(...)` call emits a separate snapshot of the scroll data.

Choose the saver that matches your output flow:

```kotlin
val scrollSaver = Saver<ScrollEntry> { scroll -> println(scroll) }
val entrySaver = EntrySaver { record -> println(record) }
```
