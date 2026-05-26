# testApp Scribe Showcase

This app is a guided demo for the current Scribe API. It runs on JVM desktop,
Android, and iOS, and writes each delivered record to the application console.
No server or local observability stack is required.

## What This Demo Covers

- An application-owned object extending `Scribe`
- `note(...)`
- `newScroll(...)` with generated and custom IDs
- Direct map-like writes on runtime-bound `Scroll`
- Map reads/removals before sealing
- `seal(...)` with success and failure outcomes
- `Margin.header(...)` and `Margin.footer(...)`
- `EntrySaver`
- Channel overflow behavior through `DROP_OLDEST`
- Saver failure reporting through `hire(onSaver = ...)`
- `retire()` and runtime re-hire
- `onIgnition` wiring without intentionally crashing the app

## Run The App

From `testApp/`:

```bash
./amper run -m jvmApp
./amper run -m androidApp
```

The UI contains demo actions for notes, scrolls, JSON serialization, queue
delivery, saver failures, and runtime shutdown. Each delivered `Entry` is
rendered as JSON and printed to stdout, while the most recent records remain
visible in the in-app timeline.

Example console output:

```json
{
  "event_kind": "scroll",
  "demo_name": "checkout_scroll",
  "scroll_id": "checkout-42",
  "success": true,
  "gateway": "stripe"
}
```

## Inspect Output

For the JVM app, records appear in the terminal where `./amper run -m jvmApp`
was started. For Android, view application stdout in Logcat or the run console.
The iOS run console likewise displays the records.

Useful fields include:

- `event_kind`
- `demo_name`
- `platform`
- `app_version`
- `saver_type`
- `tag`, `message`, `level`
- `scroll_id`, `success`
- Scroll fields such as `gateway`, `order_id`, `order_snapshot`, and `elapsed_ms`

The overflow scenario intentionally slows the console saver while using a small
dropping channel; fewer printed records than attempted notes demonstrates the
configured overflow behavior.
