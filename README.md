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
- Delivery hooks through `Archivist` instances receiving `Entry` snapshots
- Scroll lifecycle enrichment through `Margin`
- Independent `Scribe` objects for applications and imported libraries
- A JVM SLF4J 2.x provider backed by the same structured logging pipeline

## Setup

Add Scribe to your `commonMain` dependencies:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:scribe:0.6.0")
        }
    }
}
```

## Usage

Create a `Scribe` object, start processing its private buffer, and emit a scroll:

```kotlin
object AppScribe : Scribe() {
    override val archivists: List<Archivist> = listOf(
        Archivist { entry ->
            println(entry)
        }
    )
}
AppScribe.hire()

val scroll = AppScribe.newScroll()
scroll["tag"] = JsonPrimitive("payments")
scroll["message"] = JsonPrimitive("starting checkout")
scroll["level"] = JsonPrimitive("INFO")
scroll.seal(AppScribe)
```

Use a scroll when you need shared context for a longer flow:

```kotlin
object BillingScribe : Scribe() {
    override val archivists: List<Archivist> = listOf(
        Archivist { entry -> println(entry) }
    )
    override val imprint = mapOf(
        "service" to JsonPrimitive("billing"),
        "environment" to JsonPrimitive("production"),
    )
}
BillingScribe.hire()

val scroll = BillingScribe.newScroll(id = "checkout-42")
scroll["gateway"] = JsonPrimitive("stripe")
scroll["attempt"] = JsonPrimitive(1)
scroll["retry"] = JsonPrimitive(false)
scroll.seal(BillingScribe)
```

Each `Scribe` object has independent configuration and delivery lifecycle. A `Scroll` is a mutable JSON-element map initialized by `newScroll(...)`; pass the runtime that should enrich and deliver it to `scroll.seal(scribe)`. Each `seal(...)` call emits a separate snapshot of the scroll data.

## SLF4J

For JVM applications, add the SLF4J provider:

```kotlin
dependencies {
    implementation("com.rafambn:scribe-slf4j:0.6.0")
}
```

Select exactly one application-wide backend with `@ScribeBackend`:

```kotlin
@ScribeBackend
object AppScribe : Slf4jScribe() {
    override val bufferCapacity = 1_024
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
    override val archivists = listOf(
        Archivist { entry -> println(entry) },
    )

    override fun isEnabled(
        loggerName: String,
        level: Level,
        marker: Marker?,
    ): Boolean = level.toInt() >= Level.INFO.toInt()
}
```

The provider discovers the annotated backend once on the first SLF4J access and registers a JVM shutdown hook. Intake starts open, so early calls accumulate in the private buffer; the application calls `AppScribe.hire()` when processing should begin. The shutdown hook retires the Scribe and drains accepted entries automatically. Initialization fails with a descriptive error when no backend is present, multiple backends are annotated, or the annotation is not placed on a Kotlin object extending `Slf4jScribe`.

`scribe-slf4j` is a standalone SLF4J provider. Do not include another provider such as `logback-classic` in the same runtime classpath.

See the [full documentation](https://scribe.rafambn.com/) for lifecycle controls, overflow behavior, margins, and SLF4J field mapping.

## Performance

Scribe is designed for high-throughput and thread-safe concurrent logging.

The repository includes JVM throughput tests for concurrent in-memory ingestion and serialized file
writing. Results depend on the machine, runtime, buffer configuration, and archivist implementation;
run the tests in your target environment before using them for capacity planning.
