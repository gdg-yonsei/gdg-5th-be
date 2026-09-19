-- v2: 유저별 피드를 미리 만들어 둔다 (fan-out on write).
CREATE TABLE feeds (
    user_id    BIGINT      NOT NULL,
    post_id    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT feeds_pkey PRIMARY KEY (user_id, post_id)             -- 유니크 = 팬아웃을 다시 돌려도 중복 없음
);
CREATE INDEX feeds_user_created_idx ON feeds (user_id, created_at DESC, post_id DESC);
