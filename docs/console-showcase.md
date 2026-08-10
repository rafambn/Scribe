# Console Showcase

The `testApp` module is the runnable Scribe demo. It uses an application-owned
`Scribe` object and prints each delivered record as formatted JSON to the
application console. There is no external service to configure.

## What The Showcase Demonstrates

- Quick scrolls: immediately sealed one-shot events
- `newScroll(...)` with generated and custom IDs
- Direct `Scroll` map-like writes and explicit delivery runtime selection
- `extend(scroll)` and `append(key, scroll)`
- Map read/remove operations
- `seal(...)` snapshots and fail-styled scrolls (via data fields)
- `Margin`
- `Archivist` fanned out across multiple outputs
- Channel overflow behavior through `DROP_OLDEST`
- Archivist error callbacks
- reversible job pause through `dismiss()` and `hire()`
- Safe `onIgnition` wiring

## Run It

```bash
./gradlew :testApp:jvmApp:run
```

The desktop app writes records to the launching terminal. Android and iOS
records are available through their platform run consoles.

## Suggested Experiments

1. Run `Checkout flow` and inspect a wide-event JSON record.
2. Run `Map read/remove` to observe mutation before sealing.
3. Run `Margins + seal(failure)` and verify timing fields plus the `failure_reason`/`success=false` data markers.
4. Run `JSON object serialization` to inspect a nested payload.
5. Run `String template message` to inspect the `message` and `order_id` fields.
6. Run `Archivist mixed flow` to print two scroll shapes through one archivist.
7. Run `Overflow demo` and observe that a burst can be trimmed under pressure.
8. Run `Archivist failure demo` and observe the printed archivist error while delivery continues.
9. Compare `dismiss() (light queue)` with `dismiss() with backlog`.

The in-app timeline mirrors delivered console records for convenient inspection.
