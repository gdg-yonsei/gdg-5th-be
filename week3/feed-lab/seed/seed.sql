-- feed-lab 시드. 전부 generate_series + INSERT ... SELECT (행 단위 애플리케이션 코드 없음).
--   psql -v users=100000 -v posts=3000000 -v feed_days=3 -f seed.sql
-- 순서: users -> posts -> follows(자연 + 페르소나) -> 카운트 -> feeds -> 인덱스 -> seed_meta
\set ON_ERROR_STOP on
\timing on
\if :{?users}     \else \set users 100000   \endif
\if :{?posts}     \else \set posts 3000000  \endif
\if :{?feed_days} \else \set feed_days 3    \endif
-- m = 일반 유저 수 (id 11 .. users-1)
SELECT :users - 11 AS m \gset

BEGIN;   -- wal_level=minimal 이라 같은 트랜잭션에서 TRUNCATE 한 테이블은 WAL 없이 적재된다
SET LOCAL work_mem = '512MB';
SET LOCAL maintenance_work_mem = '1GB';
SET LOCAL max_parallel_maintenance_workers = 4;

TRUNCATE users, follows, posts, feeds, fanout_jobs, seed_meta RESTART IDENTITY;
-- 인덱스는 적재한 뒤에 만든다 (먼저 있으면 몇 배 느리다)
ALTER TABLE follows DROP CONSTRAINT follows_pkey;
DROP INDEX follows_followee_idx;
ALTER TABLE posts DROP CONSTRAINT posts_pkey;
DROP INDEX posts_author_idx;
ALTER TABLE feeds DROP CONSTRAINT feeds_pkey;
DROP INDEX feeds_user_created_idx;

-- 1. users ------------------------------------------------------------------------------------------
-- id 1~10 은 페르소나 자리. 나머지는 코호트(내가 팔로우하는 수 기준): light 70% / mid 27% / heavy 3%
-- 관찰자 E 만 맨 끝 id(:users)를 준다. 팬아웃은 팔로워 id 순서로 나가므로 E 는 항상 "가장 늦게 받는 팔로워"가 되고,
-- E 의 피드에 글이 뜬 시각 = 그 글의 팬아웃이 끝난 시각이 된다 (도착 배지가 매번 같은 의미를 갖는다).
INSERT INTO users (id, name, cohort) VALUES
    (1, 'A · 헤비 유저',  'persona'),   -- 3,000명 팔로우
    (2, 'B · 라이트 유저', 'persona'),   -- 20명 팔로우
    (3, 'C · 셀럽',       'persona'),   -- 팔로워 = 전체의 50%
    (4, 'D · 일반 작성자', 'persona'),   -- 팔로워 200명
    (6, '셀럽 F',         'persona'),
    (7, '셀럽 G',         'persona'),
    (8, '셀럽 H',         'persona'),
    (9, '셀럽 I',         'persona'),
    (:users, 'E · 관찰자', 'persona');   -- C, D, 셀럽들을 팔로우

CREATE TEMP TABLE follow_plan ON COMMIT DROP AS
SELECT g AS id,
       CASE WHEN r < 0.70 THEN 'light' WHEN r < 0.97 THEN 'mid' ELSE 'heavy' END AS cohort,
       CASE WHEN r < 0.70 THEN 5    + floor(random() * 46)
            WHEN r < 0.97 THEN 100  + floor(random() * 301)
            ELSE               1000 + floor(random() * 2001) END::int AS n
FROM (SELECT g, random() AS r FROM generate_series(11, :users - 1) g) s;

INSERT INTO users (id, name, cohort) SELECT id, 'user_' || id, cohort FROM follow_plan;

-- 2. posts ------------------------------------------------------------------------------------------
-- 최근 60일. 작성자는 완만한 멱법칙(활발한 소수)이고, 곱셈 순열로 섞어서 "인기"와 독립이 되게 한다.
-- (활동량 1위와 팔로워 1위가 같은 사람이 되면 feeds 가 목표의 몇 배로 불어난다)
INSERT INTO posts (author_id, content, created_at)
SELECT author_id, content, ts
FROM (
    SELECT 11 + ((floor(:m * power(random(), 1.5))::bigint * 7919 + 50021) % :m) AS author_id,
           (ARRAY['오늘 점심 뭐 먹지 고민하다가 결국 또 학식 먹었다. 내일은 꼭 밖에서 먹어야지',
                  '과제 마감이 내일인데 이제 시작함. 미래의 내가 어떻게든 하겠지',
                  '신촌에 새로 생긴 카페 다녀왔는데 자리도 넓고 콘센트도 많아서 작업하기 좋았음',
                  '서버가 또 죽었다. 로그를 보니 커넥션 풀이 말라 있었다. 왜 항상 새벽일까',
                  '주말에 한강 자전거 타고 왔다. 날씨가 좋아서 사람이 정말 많았음',
                  '이번 학기 시간표 망했다. 1교시가 네 개라니 이게 맞나',
                  '드디어 사이드 프로젝트 배포 완료. 쓰는 사람은 나밖에 없지만 뿌듯하다',
                  '인덱스 하나 걸었더니 3초 걸리던 쿼리가 20ms 가 됐다. 이 맛에 백엔드 한다',
                  '도서관 자리 잡으려고 8시에 왔는데 벌써 만석. 다들 언제 오는 거야',
                  '코드 리뷰에서 변수 이름으로 30분 토론함. 결론은 원래 이름 그대로',
                  '비 오는 날엔 역시 파전에 막걸리. 오늘은 공부 접는다',
                  '동아리 세션 발표 준비 중인데 슬라이드보다 데모 만드는 게 더 재밌다',
                  '새벽에 배포하고 자려고 누웠는데 알림이 울린다. 롤백하러 갑니다',
                  '중간고사 끝나면 하고 싶은 거 목록만 세 페이지째 쓰는 중',
                  '캐시 붙였더니 빨라지긴 했는데 이제 데이터가 안 맞는다. 세상에 공짜는 없다',
                  '오늘 처음으로 PR 이 한 번에 승인됐다. 기념으로 치킨 시킴'])[1 + floor(random() * 16)::int] AS content,
           now() - random() * interval '60 days' AS ts
    FROM generate_series(1, :posts)
    UNION ALL   -- 셀럽과 D: 60일간 40개씩
    SELECT a, '[' || (SELECT name FROM users WHERE id = a) || '] 의 지난 글 #' || g, now() - (g * 1.5 - random()) * interval '1 day'
    FROM unnest(ARRAY[3, 4, 6, 7, 8, 9]) a, generate_series(1, 40) g
    UNION ALL
    SELECT a, '[' || (SELECT name FROM users WHERE id = a) || '] 의 지난 글 #' || g, now() - (g * 10 - random()) * interval '1 day'
    FROM unnest(ARRAY[1, 2, :users]) a, generate_series(1, 5) g
) s
ORDER BY ts;   -- id 가 시간순이 되도록

-- 3. follows ----------------------------------------------------------------------------------------
-- 누구를 팔로우하나: 제곱 멱법칙(id 가 작을수록 인기). 단 상위 0.02% 는 균등으로 돌려서
-- 자연 발생 팔로워가 5천 명을 넘지 않게 한다 -> "셀럽"은 아래에서 명시적으로 만든 5명뿐 (v4 임계값 1만).
CREATE UNLOGGED TABLE follows_raw AS
SELECT follower_id,
       11 + CASE WHEN r < :m * 0.0002 THEN floor(random() * :m)::bigint ELSE r END AS followee_id
FROM (SELECT p.id AS follower_id, floor(:m * power(random(), 2))::bigint AS r
      FROM follow_plan p, generate_series(1, p.n)) s;

-- 페르소나. feeds 를 채우기 "전에" 만들어야 v1 피드와 v2 피드가 같게 나온다.
CREATE TEMP TABLE active_authors ON COMMIT DROP AS
SELECT author_id FROM posts WHERE author_id BETWEEN 11 AND :users - 1 GROUP BY 1 HAVING count(*) >= 20;

INSERT INTO follows_raw SELECT 1, author_id FROM (SELECT author_id FROM active_authors ORDER BY random() LIMIT 3000) s;   -- A
INSERT INTO follows_raw SELECT 2, author_id FROM (SELECT author_id FROM active_authors ORDER BY random() LIMIT 20) s;     -- B
INSERT INTO follows_raw SELECT :users, author_id FROM (SELECT author_id FROM active_authors ORDER BY random() LIMIT 30) s;   -- E
INSERT INTO follows_raw SELECT :users, c FROM unnest(ARRAY[3, 4, 6, 7, 8, 9]) c;                                          -- E -> C, D, 셀럽들
INSERT INTO follows_raw SELECT id, 3 FROM (SELECT id FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT (:m * 0.50)::int) s; -- C
INSERT INTO follows_raw SELECT id, 4 FROM (SELECT id FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT 200) s;             -- D
INSERT INTO follows_raw SELECT id, 6 FROM (SELECT id FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT (:m * 0.45)::int) s;
INSERT INTO follows_raw SELECT id, 7 FROM (SELECT id FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT (:m * 0.40)::int) s;
INSERT INTO follows_raw SELECT id, 8 FROM (SELECT id FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT (:m * 0.35)::int) s;
INSERT INTO follows_raw SELECT id, 9 FROM (SELECT id FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT (:m * 0.30)::int) s;

-- 중복 제거는 정렬로 한다. 기본 해시 집계는 행 수 추정이 틀려 디스크로 계속 넘치며 10분 넘게 걸린다.
SET LOCAL enable_hashagg = off;
INSERT INTO follows SELECT DISTINCT follower_id, followee_id FROM follows_raw WHERE follower_id <> followee_id;
RESET enable_hashagg;
DROP TABLE follows_raw;

ALTER TABLE follows ADD CONSTRAINT follows_pkey PRIMARY KEY (follower_id, followee_id);
CREATE INDEX follows_followee_idx ON follows (followee_id);

UPDATE users u
SET follow_count   = coalesce((SELECT count(*) FROM follows f WHERE f.follower_id = u.id), 0),
    follower_count = coalesce((SELECT count(*) FROM follows f WHERE f.followee_id = u.id), 0);

-- 4. feeds ------------------------------------------------------------------------------------------
-- 최근 N일 글만 팬아웃해 둔다 (전부 하면 수억 행). 첫 페이지(20개)는 v1 과 같게 나온다.
INSERT INTO feeds (user_id, post_id, created_at)
SELECT f.follower_id, p.id, p.created_at
FROM posts p JOIN follows f ON f.followee_id = p.author_id
WHERE p.created_at > now() - make_interval(days => :feed_days);

ALTER TABLE posts ADD CONSTRAINT posts_pkey PRIMARY KEY (id);
CREATE INDEX posts_author_idx ON posts (author_id, created_at DESC, id DESC);
ALTER TABLE feeds ADD CONSTRAINT feeds_pkey PRIMARY KEY (user_id, post_id);
CREATE INDEX feeds_user_created_idx ON feeds (user_id, created_at DESC, post_id DESC);

INSERT INTO seed_meta (max_post_id, feeds_rows, seeded_at)
SELECT (SELECT max(id) FROM posts), (SELECT count(*) FROM feeds), now();
COMMIT;

-- 인덱스 온리 스캔이 힙을 다시 보지 않도록 visibility map 을 채운다
VACUUM (ANALYZE) users;
VACUUM (ANALYZE) follows;
VACUUM (ANALYZE) posts;
VACUUM (ANALYZE) feeds;

\timing off
\echo
\echo '==== 시드 결과 ===='
SELECT (SELECT count(*) FROM users) AS users, (SELECT count(*) FROM follows) AS follows, (SELECT count(*) FROM posts) AS posts,
       (SELECT feeds_rows FROM seed_meta) AS feeds, pg_size_pretty(pg_database_size(current_database())) AS db_size;
SELECT cohort, count(*) AS users, round(avg(follow_count)) AS avg_follow, max(follow_count) AS max_follow,
       round(avg(follower_count)) AS avg_follower, max(follower_count) AS max_follower
FROM users GROUP BY 1 ORDER BY 1;
SELECT id, name, follow_count, follower_count FROM users WHERE cohort = 'persona' ORDER BY id;
