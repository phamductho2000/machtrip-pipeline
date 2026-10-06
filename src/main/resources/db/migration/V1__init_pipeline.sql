-- Everything this repo owns lives in schema "pipeline". Never reference another schema here.
CREATE SCHEMA IF NOT EXISTS pipeline;

CREATE TABLE pipeline.crawl_run (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind                 text           NOT NULL CHECK (kind IN ('search', 'comments')),
    provider             text           NOT NULL,
    actor_id             text           NOT NULL,
    actor_build          text,
    pricing_model        text,
    input                jsonb          NOT NULL,
    apify_run_id         text           NOT NULL UNIQUE,
    dataset_id           text,
    status               text           NOT NULL,
    item_count           integer,
    cost_usd             numeric(12, 6),
    max_total_charge_usd numeric(10, 4) NOT NULL,
    started_at           timestamptz    NOT NULL DEFAULT now(),
    finished_at          timestamptz
);
CREATE INDEX crawl_run_status_idx ON pipeline.crawl_run (status);

CREATE TABLE pipeline.job (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('search', 'comments')),
    apify_run_id text        NOT NULL,
    status       text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'done', 'failed')),
    attempts     integer     NOT NULL DEFAULT 0,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    locked_at    timestamptz,
    UNIQUE (apify_run_id, kind)
);
CREATE INDEX job_status_idx ON pipeline.job (status, id);

CREATE TABLE pipeline.raw_item (
    crawl_run_id bigint      NOT NULL REFERENCES pipeline.crawl_run (id),
    item_id      text        NOT NULL,
    raw_path     text        NOT NULL,
    fetched_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (crawl_run_id, item_id)
);

CREATE TABLE pipeline.video (
    tiktok_id            text PRIMARY KEY,
    web_video_url        text,
    author_hash          text,
    created_at           timestamptz,
    language             text,
    duration_sec         integer,
    play_count           bigint,
    like_count           bigint,
    save_count           bigint,
    comment_count        bigint,
    share_count          bigint,
    is_ad                boolean     NOT NULL DEFAULT false,
    is_sponsored         boolean     NOT NULL DEFAULT false,
    hashtags             text[]      NOT NULL DEFAULT '{}',
    location_name        text,
    location_address     text,
    location_city        text,
    location_country_code text,
    location_id          text,
    first_seen_at        timestamptz NOT NULL DEFAULT now(),
    last_seen_at         timestamptz NOT NULL DEFAULT now(),
    -- not in the original column list: needed for the "skip recently crawled" comments filter
    comments_crawled_at  timestamptz
);

CREATE TABLE pipeline.video_subtitle (
    video_id   text        NOT NULL REFERENCES pipeline.video (tiktok_id),
    language   text        NOT NULL,
    source     text        NOT NULL,
    version    text        NOT NULL,
    trusted    boolean     NOT NULL,
    vtt_text   text,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, language, source, version)
);

CREATE TABLE pipeline.video_audio (
    video_id     text PRIMARY KEY REFERENCES pipeline.video (tiktok_id),
    storage_path text        NOT NULL,
    fetched_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pipeline.video_asr_queue (
    video_id   text PRIMARY KEY REFERENCES pipeline.video (tiktok_id),
    reason     text        NOT NULL CHECK (reason IN ('no_subtitle', 'untrusted_version', 'low_quality')),
    status     text        NOT NULL DEFAULT 'pending',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pipeline.comment (
    id           text PRIMARY KEY,
    video_id     text        NOT NULL REFERENCES pipeline.video (tiktok_id),
    author_hash  text        NOT NULL,
    text         text,
    like_count   bigint,
    reply_count  bigint,
    created_at   timestamptz,
    parent_id    text
);
CREATE INDEX comment_video_idx ON pipeline.comment (video_id);
