# Lifecycle and Delivery

## Private Buffer

Each `Scribe` owns its delivery buffer. Callers configure its capacity and overflow policy,
but never own or close the underlying `Channel`:

```kotlin
object CheckoutScribe : Scribe() {
    override val bufferCapacity = 256
    override val bufferOverflow = BufferOverflow.DROP_OLDEST
    override val onArchiveFailure: ((Archivist, Entry, Throwable) -> Unit)? = null
    override val archivists = listOf(Archivist { entry -> println(entry) })
}
```

Intake starts open and the job starts dismissed. Entries sealed before `hire()` remain in the
buffer according to the configured overflow policy.

## Independent Controls

Intake and the processing job are independent:

| Intake | Job | Behavior |
|---|---|---|
| open | dismissed | new entries accumulate in the buffer |
| open | hired | new and buffered entries are delivered |
| closed | hired | no new entries are accepted; the backlog keeps draining |
| closed | dismissed | no intake or delivery occurs; the backlog is preserved |

```kotlin
CheckoutScribe.hire()         // start or resume the job
CheckoutScribe.dismiss()      // request a cooperative pause immediately
CheckoutScribe.closeIntake()
CheckoutScribe.openIntake()
```

Archivist failure handling is configured by the implementation's `onArchiveFailure` property.
Calling `hire()`
while processing is active has no effect. `dismiss()` is reversible, returns immediately, and does
not close intake. An entry already received by the worker may finish or remain held at the pause
gate; all other entries stay in the private buffer until the next `hire()`.

Archivists process each entry concurrently. The processor waits for every archivist to finish
before consuming the next entry, preserving entry order for each archivist while preventing one
archivist from delaying the start of its peers.

## Emission

`seal(scribe)` applies the footer margin, snapshots the current `Scroll`, and attempts to place the
resulting `Entry` in the private buffer. It is non-suspending and never blocks waiting for buffer
space. Entries rejected because intake is closed, the buffer is full with `SUSPEND`, or retirement
has begun are not delivered. Prefer `DROP_OLDEST` or `DROP_LATEST` for synchronous logging.

`seal(...)` always returns the created snapshot; its return value does not indicate whether the
buffer accepted it. With `DROP_LATEST`, the channel can report a successful send while discarding
the new entry according to that overflow policy.

Multiple calls to `seal(...)` on the same `Scroll` intentionally create separate snapshots.

## Terminal Retirement

`retire()` is distinct from the reversible `dismiss()`:

```kotlin
CheckoutScribe.retire()
```

It closes intake and the private buffer, finishes the active archivist call, drains all accepted
entries, and releases the internally owned scope. Intake and processing cannot restart
afterward.

Concurrent or repeated callers wait for the same retirement operation. Cancelling one waiting
caller does not cancel the drain, and a later `retire()` call can still await its completion.
Calling `retire()` from an archivist or one of its child coroutines throws an
`IllegalStateException` to avoid waiting on the processor from within its own job tree; request
retirement from the application lifecycle owner instead.

The JVM SLF4J provider registers a shutdown hook that calls `retire()` automatically. It waits up to
the backend's configured `shutdownTimeout`, five seconds by default, then allows JVM shutdown to
continue even if an archivist is stuck. It does not call `hire()`: the application chooses when
processing begins, while earlier SLF4J calls accumulate in the backend's private buffer.

## Uncaught Exceptions

Override `onIgnition` on an application-owned `Scribe` to observe platform-level failures. The
callback is registered on the first `hire()` and unregistered during `retire()`. Scribe installs
one internal platform dispatcher and delivers each failure once to every active Scribe callback;
callback failures are isolated and the previous platform handler is preserved. Browser JS and
wasmJs observe synchronous global errors and unhandled Promise rejections. Node observes uncaught
exceptions through `uncaughtExceptionMonitor`, using its `origin` argument to distinguish ordinary
uncaught exceptions from promoted unhandled rejections, without replacing Node's normal termination
path. A rejection is visible there only when the host's current `--unhandled-rejections` policy
promotes it. wasmWasi has no portable global hook, so a non-null `onIgnition` makes `hire()` fail with
an unsupported-operation message; normal logging works when it is null. Fatal delivery is best effort
and does not wait for the asynchronous Scribe buffer to drain. Archivist failures are handled
separately by the implementation's `onArchiveFailure` property.
