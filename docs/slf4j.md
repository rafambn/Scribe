# SLF4J Provider

The `scribe-slf4j` module is a JVM SLF4J 2.x provider. It converts conventional SLF4J calls into
Scribe `Entry` snapshots and sends them through one application-wide `Slf4jScribe` backend.

## Install

Add the provider to a JVM application's runtime dependencies:

```kotlin
dependencies {
    implementation("com.rafambn:scribe-slf4j:0.6.0")
}
```

The module exposes the SLF4J API and the core `scribe` module transitively. It is itself an SLF4J
provider, so do not put another provider such as `logback-classic` on the same runtime classpath.

## Define the Backend

Annotate exactly one Kotlin `object` that extends `Slf4jScribe`:

```kotlin
@ScribeBackend
object AppScribe : Slf4jScribe() {
    override val bufferCapacity = 1_024
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val archivists = listOf(
        Archivist { entry -> println(entry) },
    )

    override val onArchiveFailure =
        { _: Archivist, _: Entry, error: Throwable -> error.printStackTrace() }

    override fun isEnabled(
        loggerName: String,
        level: Level,
        marker: Marker?,
    ): Boolean = level.toInt() >= Level.INFO.toInt()
}
```

The provider scans the runtime classpath on its first initialization. Initialization fails if it
finds no annotated backend, more than one, a class that does not extend `Slf4jScribe`, or an
annotated class that is not a Kotlin object.

## Start Processing

SLF4J calls are accepted as soon as the provider is initialized, but processing starts dismissed.
Call `hire()` during application startup:

```kotlin
fun main() {
    AppScribe.hire()

    val logger = LoggerFactory.getLogger("checkout")
    logger.info("Starting order {}", 42)
}
```

Calls made before `hire()` accumulate according to the backend's capacity and overflow policy.
The provider registers a JVM shutdown hook that calls `retire()` and drains accepted entries. You
may also retire explicitly from your lifecycle owner; repeated calls await the same terminal
retirement operation.

## Default Entry Mapping

The default `handleNormalizedLoggingCall(...)` uses SLF4J's message formatter and writes these
fields:

| Field | When present | Value |
|---|---|---|
| `scroll_id` | always | generated Scribe scroll ID |
| `level` | always | SLF4J level name |
| `logger` | always | requested logger name |
| `message` | always | formatted message |
| `marker` | when supplied | marker name |
| `exception` | when supplied | exception stack trace |

The current MDC map is copied into the entry. MDC keys cannot replace the reserved fields above,
and they also do not replace fields already supplied by the backend's imprint or header margin.

`isEnabled(loggerName, level, marker)` runs before a scroll is allocated. It can filter by logger,
level, and marker. All levels are disabled automatically while intake is closed or retirement is
in progress.

## Customize the Mapping

Override `handleNormalizedLoggingCall` when the default field names or message formatting do not
fit your schema. The call contains the logger name, level, marker, raw message pattern, arguments,
throwable, and an MDC snapshot:

```kotlin
override fun handleNormalizedLoggingCall(call: ScribeLoggingCall) {
    val scroll = newScroll()
    scroll["severity"] = JsonPrimitive(call.level.name)
    scroll["source"] = JsonPrimitive(call.loggerName)
    scroll["template"] = JsonPrimitive(call.messagePattern.orEmpty())
    call.mdc["requestId"]?.let { scroll["request_id"] = JsonPrimitive(it) }
    scroll.seal(this)
}
```

The override owns the mapping and must seal a scroll itself to deliver an entry. Call
`super.handleNormalizedLoggingCall(call)` instead when the default mapping is desired.
