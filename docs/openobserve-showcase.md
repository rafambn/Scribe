# OpenObserve Showcase

The `testApp` module is the practical demo for Scribe. It turns the library API into a guided UI and ships a local OpenObserve setup under `testApp/openobserve/` so you can see Scribe events land in a real observability tool.

## What The Showcase Demonstrates

The app covers the current public runtime features of Scribe:

- `note(...)`
- `flingNote(...)`
- `unrollScroll(...)`
- generated and custom scroll IDs
- `writeString`, `writeNumber`, `writeBoolean`, `writeSerializable`
- `read(...)`, `erase(...)`, `seekScrolls()`
- `seal(...)`, `looseSeal(...)`
- `Margin`
- `NoteSaver`, `ScrollSaver`, `EntrySaver`
- `ScribeDeliveryConfig`
- `onSaverError`
- `retire()` and `planRetire()`
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
Scrolls contribute `scroll_id`, `success`, `error_message`, `context`, and `data`.

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
2. Run `Read/erase/seek` to see custom scroll IDs and scroll mutation helpers in action.
3. Run `Margins + looseSeal` and verify the timing fields in the uploaded scroll.
4. Run `EntrySaver mixed flow` to send a note and a scroll through one saver path.
5. Run `Overflow demo` and confirm that not every emitted event survives the configured buffer policy.
6. Run `Saver failure demo` and observe that `onSaverError` reports the failure while the next saver still uploads the record.
7. Compare `retire()` with `planRetire()` by running both shutdown demos and inspecting the in-app timeline.

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

For setup details and the exact run commands, see `testApp/README.md` in the repository root.
