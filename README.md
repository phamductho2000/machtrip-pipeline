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
| `search --hashtag X[,Y,...] --limit N [--dry-run]` | start a search run for one or more hashtags (caps always sent) |
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

### HTTP API

Everything the CLI can do (except `ingest`, which needs a local file) is also reachable over HTTP while
`serve-webhook` is running, under `/api/v1`. Every request needs header `X-API-Key: $API_KEY`; a blank `API_KEY`
makes the API refuse all requests (fail closed, same pattern as the webhook secret). This is meant for a trusted
caller (an internal admin tool, another backend) — not for exposing the crawl trigger publicly, since each real run
spends real money.

| Endpoint | Mirrors | Body / params |
|---|---|---|
| `POST /api/v1/runs/search` | `search` | `{"hashtags":["X","Y"],"limit":N,"dryRun":false}` |
| `POST /api/v1/runs/comments` | `comments` | `{"limit":N,"dryRun":false}` |
| `POST /api/v1/runs/{crawlRunId}/resume` | `resume <id>` | - |
| `POST /api/v1/work` | `work --once` | - (always drains once; the continuous loop stays CLI-only) |
| `POST /api/v1/reconcile` | `reconcile` | - |
| `GET /api/v1/stats` | `stats` | - |
| `POST /api/v1/validate-input?kind=search\|comments` | `validate-input` | - (real Apify call, see below) |

```bash
curl -X POST localhost:8080/api/v1/runs/search -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
     -d '{"hashtags":["reviewdalat"],"limit":5,"dryRun":true}'
```

Responses are JSON; validation errors (e.g. missing `limit`) come back as `400` with `{"error":"..."}`, a config
problem (e.g. `resume` before `PUBLIC_WEBHOOK_BASE_URL`/`WEBHOOK_SECRET` are set) as `409`, and an Apify-side failure
with Apify's own status code (or `502` for a transport error), same mapping as the CLI's error messages.

### Flow

1. `search` / `comments` start a run with `maxTotalChargeUsd`, `timeout`, `maxItems`, `waitForFinish` and an ad-hoc
   webhook (`ACTOR.RUN.SUCCEEDED|FAILED|TIMED_OUT|ABORTED`). `crawl_run` (run id + dataset id) is written right after.
2. Apify calls `POST /webhooks/apify/{WEBHOOK_SECRET}`. The handler compares the secret in constant time, takes only the
   run id from the payload, re-fetches `GET /actor-runs/{id}`, updates `crawl_run` and inserts one `pipeline.job`
   (`ON CONFLICT DO NOTHING`). Nothing is downloaded there. If Apify cannot be reached it answers 502 so Apify redelivers.
3. `work` ingests the dataset: raw JSON to `RAW_STORE_DIR/{runId}.json.gz` -> upsert `video` -> trusted subtitle VTT
   downloaded immediately, otherwise ASR queue (+ audio download for `musicOriginal` videos that pass the filters).
   For a `comments` job the default dataset actually holds *video* items (each carrying `commentsDatasetUrl`, a
   separate dataset shared by every video in that run); `work` fetches that URL too and upserts the real comments
   into `pipeline.comment` (matched to a video by the numeric id in `submittedVideoUrl`/`videoWebUrl` -- a comment
   for a video outside `pipeline.video` is skipped, not fatal). That extra read is billed as a tiny Apify
   storage-operation charge, separate from and far smaller than the run's own cost.
   Jobs are retried up to `pipeline.job.max-attempts`, then `failed` with `last_error`.
4. If a webhook never arrives: `reconcile` (or `resume <id>`).

### Cost control

Every start sends `maxTotalChargeUsd` (default 1.0) and `timeout`; `StartRequest` refuses to exist without them.
`clockworks~tiktok-scraper` is **pay-per-event**, so `maxItems` does not apply to it; the real item limit is
`resultsPerPage` inside the actor input (`config/actors/search.json`, `${limit}` is mandatory there) plus
`maxTotalChargeUsd`.

### Actor input = configuration

`config/actors/{search,comments}.json` are templates (`"${hashtags}"`, `"${limit}"`, `"${videoUrls}"`,
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

## Stage 2: extraction (LLM)

For each video, an LLM reads what stage 1 collected and returns structured **mentions** (places, prices, tips,
warnings, ...), each with an `evidence.quote` that code verifies against the input. All LLM calls go through the Genway
gateway (`GENWAY_BASE_URL`, `GENWAY_API_KEY`); there is no provider SDK and no provider URL anywhere.

`GenwayLlmClient` is implemented strictly from `docs/genway-llm-api.md`: `POST /api/generation` with
`{model_key, call_type: "text", input: {messages, max_tokens}}`, `Authorization: Bearer`, and an `Idempotency-Key`
header (`machtrip-pipeline-extract-<sha256 of video|prompt version|model|attempt>`; a `--force` run adds a nonce, because
Genway answers a repeated key with the original request). Genway already retries provider 429/5xx, so HTTP errors and
`success:false` bodies are final for the video; only connection errors and timeouts of our own call are retried (max 2).
Two things the doc does not say and that the first real call must confirm: where the system prompt goes
(`genway.system-prompt-as: message|field`, default `message`) and the shape of `data.response` for text models (a plain
string, an OpenAI-style chat completion and an Anthropic-style message are recognised; anything else fails with a clear
error). Text models listed in the doc: `claude-sonnet-5`, `deepseek-flash`, `deepseek-v4-pro`, `gpt-4o`; which of them
your tenant may call is up to the Genway admin. Tests use `FakeLlmClient` and WireMock.

| Command | What it does |
|---|---|
| `extract --limit N [--video id] [--dry-run] [--prompt-version v1] [--model m] [--force]` | extract videos not yet done for (prompt version, model); `--dry-run` prints the exact prompt hash, the exact user content and the estimated cost, with no network call |
| `extract-report [--limit N] [--model m] [--out reports/]` | Markdown review file: caption/excerpts, accepted mentions with evidence, rejected mentions with reasons, totals |
| `extract-eval --gold gold/extract_gold.jsonl [--model m]` | precision/recall of `place` mentions against your hand-labeled gold file (see `gold/README.md`) |
| `extract-compare --models a,b --gold ... --limit N` | the same videos through several models; one table (precision, recall, rejected rate, tokens, cost) |
| `extract-stats` | counts by status, kind, rejection reason; total cost |

**Input** (`ExtractionInputBuilder`): `<caption>`, `<hashtags>`, `<location_tag>`, the trusted Vietnamese subtitle as
`[mm:ss] text` lines, and the top comments by likes as `<comment id="..." likes="N">`. Everything from the video is
untrusted: `<` and `>` inside it are replaced by `‹` `›`, so it cannot close or open a delimiter. No hashes, usernames
or user ids are sent. Videos with neither a trusted transcript text nor comments are recorded as `skipped`
(`no_transcript_no_comments`); videos in `video_asr_queue` are out of scope. The caption is not a column of
`pipeline.video`; it is read from the raw provider JSON stage 1 keeps (`raw_item.raw_path`).

**Validation** (code, after every answer): the JSON must match `prompts/extract_v1.schema.json` (one repair attempt,
then the video is `failed`); a mention whose `evidence.quote` is not in the input text of its source (after whitespace
and case normalisation, diacritics kept) is dropped into `mention_rejected`; tags outside `config/tags.yml` are
removed; `energy` outside 0..1 (or not a place) becomes null; price numbers that do not match the digits of
`price_text` become null.

**Cost and safety**: `extract.max-cost-usd-per-run` (default 0.50) is checked *before* every call against a worst-case
estimate (input tokens + `max-output-tokens`), and the run stops instead of exceeding it. Real runs refuse to start
until `extract.model` and `extract.pricing.*` are set (both are TODO in `application.yml`). Concurrency defaults to 2.
Each raw answer is stored gzip under `RAW_STORE_DIR/extractions/`.

Prompts live in `src/main/resources/prompts/extract_vN.md` + `.schema.json`; the closed tag list is injected from
`config/tags.yml` (starter list, to be reviewed).
