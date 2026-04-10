# testApp Scribe Showcase

This app is a guided demo for the current Scribe API in this repository. It runs on JVM desktop and Android, uploads demo records into a local OpenObserve instance, and showcases runtime behavior end-to-end without requiring any backend outside this folder.

## What This Demo Covers

- `note(...)`
- `newScroll(...)` with generated and custom IDs
- direct map writes on `Scroll` (`scroll["field"] = ...`)
- map reads/removals before sealing
- `seal(...)` with success and failure outcomes
- `Margin.header(...)`, `Margin.footer(...)`
- `EntrySaver`
- channel overflow behavior via `Channel(..., onBufferOverflow = DROP_OLDEST)`
- saver failure callback through `hire(onSaver = ... )`
- `retire()` and runtime re-hire
- `onIgnition` wiring, documented safely without crashing the app

All uploaded records go into one OpenObserve stream named `scribe_demo`. Sparse fields are intentional. Every record includes `event_kind` so you can query notes and scrolls separately inside the same stream.

## Folder Layout

```text
testApp/
  android-app/
  jvm-app/
  ios-app/
  shared/
  openobserve/
    compose.yaml
    .env.example
    data/
    start-openobserve.sh
    stop-openobserve.sh
    reset-openobserve.sh
```

`openobserve/data/` is the local persisted database directory used by the container.

## Start OpenObserve

From `testApp/`:

```bash
cd openobserve
cp .env.example .env
./start-openobserve.sh
```

OpenObserve will be available at:

- URL: `http://localhost:5080`
- Username: `root@example.com`
- Password: `Complexpass#123`

If you want to clear the local database and start fresh:

```bash
cd openobserve
./reset-openobserve.sh
./start-openobserve.sh
```

## Run The App

From `testApp/`:

```bash
./amper run -m jvm-app
./amper run -m android-app
```

The UI contains grouped demo actions:

- `Run note(...)`
- `Run second note(...)`
- `Checkout flow`
- `Map read/remove`
- `Margins + seal(failure)`
- `JSON object serialization`
- `String template message`
- `EntrySaver mixed flow`
- `Overflow demo`
- `Saver failure demo`
- `retire() (light queue)`
- `retire() with backlog`
- `Wire onIgnition`

Each action updates the in-app timeline and attempts an upload to OpenObserve.

## Inspect The Data In OpenObserve

After running a few scenarios, log into OpenObserve and query the `scribe_demo` stream.

In the OpenObserve UI:

1. Open `http://localhost:5080/web/`
2. Sign in with the local root credentials from `.env`
3. Make sure the selected organization is `default`
4. Open the logs view and select the `scribe_demo` stream
5. Expand the time picker to include the last few minutes if you do not immediately see records

Important detail:

- The app uploads successfully even if the UI is still pointed at another stream
- The records only appear after you switch to `scribe_demo`
- Because this demo uses a single stream with sparse fields, some columns will be empty for notes and others will be empty for scrolls
- The app’s OpenObserve status check may report `308` on the root URL; that is expected because OpenObserve redirects `/` to `/web/`

Useful filters:

```sql
event_kind = 'note'
```

```sql
event_kind = 'scroll'
```

```sql
demo_name = 'overflow_demo'
```

```sql
demo_name = 'saver_failure'
```

Fields you will see often:

- `event_kind`
- `demo_name`
- `platform`
- `app_version`
- `saver_type`
- `tag`, `message`, `level`
- `scroll_id`, `success`, `error_message`
- scroll data fields such as `gateway`, `order_id`, `order_snapshot`, `elapsed_ms`

Useful first checks if uploads returned HTTP 200 but you do not see data:

```text
1. Confirm you selected the scribe_demo stream
2. Confirm the time range includes the event timestamp
3. Filter by demo_name or event_kind instead of scanning all rows
4. Refresh the query after running a scenario
```

## Notes About The Demo

- The app is configured for a local OpenObserve instance on `http://localhost:5080`.
- The Android and JVM targets are the intended showcase targets.
- The iOS target still compiles with the shared screen, but the local OpenObserve workflow is primarily documented for desktop and Android experimentation.
- `onIgnition` is wired in the app, but the UI does not intentionally trigger an uncaught exception because that would terminate the demo process.
