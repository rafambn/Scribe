# Console Showcase

The `testApp` module is the runnable Scribe demo. It uses an application-owned
`Scribe` object and prints each delivered record as formatted JSON to the
application console. There is no external service to configure.

## What The Showcase Demonstrates

- `note(...)`
- `newScroll(...)` with generated and custom IDs
- Direct `Scroll` map-like writes and explicit delivery runtime selection
- `extend(scroll)` and `append(key, scroll)`
- Map read/remove operations
- `seal(...)` success and failure outcomes
- `Margin`
- `EntrySaver`
- Channel overflow behavior through `DROP_OLDEST`
- Saver error callbacks
- `retire()` and runtime re-hire
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
3. Run `Margins + seal(failure)` and verify timing fields plus `success = false`.
4. Run `JSON object serialization` to inspect a nested payload.
5. Run `String template message` to inspect the `message` and `order_id` fields.
6. Run `EntrySaver mixed flow` to print a note and a scroll through one saver.
7. Run `Overflow demo` and observe that a burst can be trimmed under pressure.
8. Run `Saver failure demo` and observe the printed saver error while delivery continues.
9. Compare `retire() (light queue)` with `retire() with backlog`.

The in-app timeline mirrors delivered console records for convenient inspection.
