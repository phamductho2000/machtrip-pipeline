# machtrip-pipeline

Mách Trip data pipeline, **stage 1: crawl TikTok through Apify** (actor `clockworks~tiktok-scraper`).
Java 21, Spring Boot 3.5, plain SQL (`JdbcClient`), Flyway, picocli. No AI/ASR/OCR here.

The Postgres database `machtrip` is shared with the backend. **This repo owns only schema `pipeline`**: Flyway is
pinned to it (`spring.flyway.schemas/default-schema=pipeline`, history table `pipeline.flyway_schema_history`), and
every connection has `search_path = pipeline`.

## Run

```bash
cp .env.example .env   # fill in, then export the variables (Spring reads real env vars, not .env)
./mvnw verify          # tests need Docker (Testcontainers, image postgres:16-alpine); no internet, no real Apify
java -jar target/machtrip-pipeline.jar <command>
```

| Command | What it does |
|---|---|
| `search --hashtag X --limit N [--dry-run]` | start a search run (caps always sent) |
| `comments --limit N [--dry-run]` | comments run for videos that pass `config/filters.yml` (N = max videos) |
| `resume <crawl_run_id>` | re-check one run with Apify; queue its ingest job (requeue it if it failed) |
| `work [--once]` | worker loop over `pipeline.job` (`FOR UPDATE SKIP LOCKED`); `--once` drains and exits |
| `reconcile` | polling fallback: check runs stuck non-terminal longer than `pipeline.reconcile.stale-after` |
| `serve-webhook` | start the web server for `POST /webhooks/apify/{secret}` |
| `validate-input --kind search\|comments` | Apify "Validate Actor input" (real call, see below) |
| `ingest --file <dataset.json> [--kind]` | load an Apify dataset export offline (no network, no downloads) |
| `stats` | counts of what is in schema `pipeline` |

### Web server vs CLI

`Application.main` picks the mode from the first argument: only `serve-webhook` starts the servlet container; every
other command runs with `web-application-type=none` (same as `--spring.main.web-application-type=none`).
The server binds to `127.0.0.1:8080` by default (`server.address`, `server.port`; env `SERVER_ADDRESS`, `SERVER_PORT`).
Put a tunnel / reverse proxy in front and set `PUBLIC_WEBHOOK_BASE_URL` to its public https URL.

### Flow

1. `search` / `comments` start a run with `maxTotalChargeUsd`, `timeout`, `maxItems`, `waitForFinish` and an ad-hoc
   webhook (`ACTOR.RUN.SUCCEEDED|FAILED|TIMED_OUT|ABORTED`). `crawl_run` (run id + dataset id) is written right after.
2. Apify calls `POST /webhooks/apify/{WEBHOOK_SECRET}`. The handler compares the secret in constant time, takes only the
   run id from the payload, re-fetches `GET /actor-runs/{id}`, updates `crawl_run` and inserts one `pipeline.job`
   (`ON CONFLICT DO NOTHING`). Nothing is downloaded there. If Apify cannot be reached it answers 502 so Apify redelivers.
3. `work` ingests the dataset: raw JSON to `RAW_STORE_DIR/{runId}.json.gz` -> upsert `video` -> trusted subtitle VTT
   downloaded immediately, otherwise ASR queue (+ audio download for `musicOriginal` videos that pass the filters).
   Jobs are retried up to `pipeline.job.max-attempts`, then `failed` with `last_error`.
4. If a webhook never arrives: `reconcile` (or `resume <id>`).

### Cost control

Every start sends `maxTotalChargeUsd` (default 1.0) and `timeout`; `StartRequest` refuses to exist without them.
`clockworks~tiktok-scraper` is **pay-per-event**, so `maxItems` does not apply to it; the real item limit is
`resultsPerPage` inside the actor input (`config/actors/search.json`, `${limit}` is mandatory there) plus
`maxTotalChargeUsd`.

### Actor input = configuration

`config/actors/{search,comments}.json` are templates (`"${hashtag}"`, `"${limit}"`, `"${videoUrls}"`,
`"${maxPerVideo}"`). Keys starting with `_` are notes and are never sent. Loading fails at start on a missing
placeholder, an unknown placeholder, or a value that still starts with `TODO`.

`validate-input` calls `POST /v2/actors/{id}/validate-input`. **Apify's docs do not say whether this call is free**;
it was therefore not called while building this.

### Privacy

`author_hash = sha256(HASH_SALT + authorMeta.id)` (falls back to `authorMeta.name` if there is no id). Raw usernames
are never written to the database. Known exceptions, see "assumptions" in the hand-over notes:
`video.web_video_url` and the stored comments-run input contain `@handle` URLs, and the raw gzip files are the
provider JSON as received.

### Troubleshooting (Windows)

If the JVM fails with `Unable to establish loopback connection` (a very long user TEMP path breaks the AF_UNIX socket
JDK 21 uses for NIO selectors), add `-Djdk.net.unixdomain.tmpdir=C:\tmp` to the `java` command. The Maven build sets
this for the tests already.
