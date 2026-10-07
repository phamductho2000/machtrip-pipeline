-- Stage 2 (extraction). New tables only, all inside schema "pipeline".
CREATE TABLE pipeline.video_extraction (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    video_id         text           NOT NULL REFERENCES pipeline.video (tiktok_id),
    prompt_version   text           NOT NULL,
    model            text           NOT NULL,
    status           text           NOT NULL CHECK (status IN ('ok', 'failed', 'skipped')),
    skip_reason      text,
    error            text,
    input_tokens     integer,
    output_tokens    integer,
    tokens_estimated boolean        NOT NULL DEFAULT false,
    cost_usd         numeric(12, 6),
    truncated        boolean        NOT NULL DEFAULT false,
    raw_response_path text,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (video_id, prompt_version, model)
);

CREATE TABLE pipeline.mention (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    extraction_id    bigint      NOT NULL REFERENCES pipeline.video_extraction (id),
    video_id         text        NOT NULL REFERENCES pipeline.video (tiktok_id),
    kind             text        NOT NULL CHECK (kind IN ('place', 'tip', 'warning', 'price', 'transport', 'status_update')),
    name_raw         text,
    name_confidence  text        NOT NULL CHECK (name_confidence IN ('high', 'medium', 'low')),
    category         text,
    text             text        NOT NULL,
    price_text       text,
    price_vnd_min    bigint,
    price_vnd_max    bigint,
    sentiment        text        NOT NULL CHECK (sentiment IN ('positive', 'neutral', 'negative', 'mixed')),
    sponsored_signal boolean     NOT NULL DEFAULT false,
    energy           double precision CHECK (energy IS NULL OR (energy >= 0 AND energy <= 1)),
    tags             text[]      NOT NULL DEFAULT '{}',
    best_for         text[]      NOT NULL DEFAULT '{}',
    evidence         jsonb       NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mention_extraction_idx ON pipeline.mention (extraction_id);
CREATE INDEX mention_video_idx ON pipeline.mention (video_id);

-- Mentions the code dropped (quote not in input, ...) so what the model made up can be audited.
CREATE TABLE pipeline.mention_rejected (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    extraction_id bigint      NOT NULL REFERENCES pipeline.video_extraction (id),
    reason        text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mention_rejected_extraction_idx ON pipeline.mention_rejected (extraction_id);
