-- v1: 글, 팔로우, 유저. 피드는 읽을 때 모은다.
CREATE TABLE users (
    id             BIGINT PRIMARY KEY,
    name           TEXT   NOT NULL,
    cohort         TEXT   NOT NULL,           -- light | mid | heavy | persona
    follow_count   INT    NOT NULL DEFAULT 0, -- 내가 팔로우하는 수
    follower_count INT    NOT NULL DEFAULT 0  -- 나를 팔로우하는 수 (v4 임계값 판단용)
);

CREATE TABLE follows (
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    CONSTRAINT follows_pkey PRIMARY KEY (follower_id, followee_id)   -- "내가 팔로우하는 사람들"
);
CREATE INDEX follows_followee_idx ON follows (followee_id);          -- "나를 팔로우하는 사람들" (팬아웃용)

CREATE TABLE posts (
    id         BIGSERIAL   NOT NULL,
    author_id  BIGINT      NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT posts_pkey PRIMARY KEY (id)
);
CREATE INDEX posts_author_idx ON posts (author_id, created_at DESC, id DESC);   -- v1 LATERAL, v4 셀럽 pull

CREATE TABLE seed_meta (
    max_post_id BIGINT      NOT NULL,
    feeds_rows  BIGINT      NOT NULL,
    seeded_at   TIMESTAMPTZ NOT NULL
);
