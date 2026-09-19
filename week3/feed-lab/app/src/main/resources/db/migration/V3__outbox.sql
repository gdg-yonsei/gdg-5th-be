-- v3: 팬아웃을 요청 밖으로 뺀다. 글과 같은 트랜잭션에서 잡을 남기고 워커가 처리한다.
CREATE TABLE fanout_jobs (
    id           BIGSERIAL   PRIMARY KEY,
    post_id      BIGINT      NOT NULL,
    strategy     TEXT        NOT NULL,              -- full(v3) | hybrid(v4)
    status       TEXT        NOT NULL,              -- pending | done | skipped
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    fanout_count INT         NOT NULL DEFAULT 0
);
CREATE INDEX fanout_jobs_status_idx ON fanout_jobs (status, id);
