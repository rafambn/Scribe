# OpenObserve Showcase

The `testApp` module is the practical demo for Scribe. It turns the current library API into a guided UI and ships a local OpenObserve setup under `testApp/openobserve/` so you can see events land in a real observability tool.

## What The Showcase Demonstrates

The app covers the current public runtime features of Scribe:

- `note(...)`
- `newScroll(...)` with generated and custom IDs
- direct `Scroll` map writes (`scroll["field"] = ...`)
- `extend(scroll)` to copy missing keys from another scroll
- `append(key, scroll)` to nest a scroll as a JSON object
- map read/remove operations
- `seal(...)` with success and failure outcomes
- `Margin`
- `EntrySaver`
- channel overflow behavior via `Channel(..., onBufferOverflow = DROP_OLDEST)`
- saver error callback through `hire(onSaver = ... )`
- `retire()` and runtime re-hire
- safe `onIgnition` wiring

## Single Stream Design

The showcase uses one OpenObserve stream named `scribe_demo`.

That stream intentionally allows sparse fields. Every uploaded record includes:

- `event_kind`
- `demo_name`
- `platform`
- `app_version`
- `saver_type`

Notes then contribute fields such as `tag`, `message`, `level`, and `note_timestamp`.
Scrolls contribute `scroll_id`, `success`, and fields from `SealedScroll.data`.

The single-stream design makes it easy to search everything in one place while still filtering by `event_kind`.

## Local OpenObserve Setup

The OpenObserve stack lives under `testApp/openobserve/`:

```text
testApp/openobserve/
  compose.yaml
  .env.example
  data/
  start-openobserve.sh
  stop-openobserve.sh
  reset-openobserve.sh
```

`data/` is bind-mounted into the container and acts as the local persisted database for the demo.

Start it with:

```bash
cd testApp/openobserve
cp .env.example .env
./start-openobserve.sh
```

Then run the demo app:

```bash
cd testApp
./amper run -m jvm-app
```

or:

```bash
./amper run -m android-app
```

## Suggested Experiments

1. Run `Checkout flow` and inspect the wide-event payload in `scribe_demo`.
2. Run `Map read/remove` to inspect map mutation behavior before sealing.
3. Run `Margins + seal(failure)` and verify timing fields plus `success = false`.
4. Run `JSON object serialization` to validate nested payload fields in OpenObserve.
5. Run `String template message` to inspect message rendering in the `message` field.
6. Run `EntrySaver mixed flow` to send a note and a scroll through one saver path.
7. Run `Overflow demo` and confirm a burst can be trimmed by `DROP_OLDEST` under pressure.
8. Run `Saver failure demo` and observe that `onSaver` reports the injected failure while delivery continues.
9. Compare `retire() (light queue)` with `retire() with backlog` in the in-app timeline.
10. Run `Extend scroll` to verify keys are copied only when missing from the target scroll.
11. Run `Append scroll` to validate nested JSON object creation in OpenObserve.

## Querying In OpenObserve

Examples:

```sql
event_kind = 'note'
```

```sql
event_kind = 'scroll'
```

```sql
demo_name = 'margin_scroll'
```

```sql
demo_name = 'overflow_demo'
```

UI checks that matter:

- Open `http://localhost:5080/web/`, not just the root URL
- Select the `scribe_demo` stream in the logs UI
- Keep the organization as `default`
- Widen the time picker if the stream looks empty after a successful upload
- Filter by `event_kind` or `demo_name` first, because the single-stream demo intentionally has sparse fields

For setup details and exact run commands, see `testApp/README.md` in the repository root.
