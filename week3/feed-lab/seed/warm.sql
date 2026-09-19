-- 테이블과 인덱스를 메모리에 올린다. 콜드/웜 편차가 크다 (v1 헤비 조회: 콜드 3.5초, 웜 45ms).
-- 덜 중요한 것부터 올린다. shared_buffers 에 다 안 들어가면 뒤에 올린 것이 남는다 (나머지는 OS 캐시).
CREATE EXTENSION IF NOT EXISTS pg_prewarm;
SELECT r AS relation, pg_prewarm(r::regclass) AS pages
FROM unnest(ARRAY['feeds', 'follows', 'feeds_pkey', 'follows_followee_idx', 'feeds_user_created_idx',
                  'users', 'users_pkey', 'follows_pkey', 'posts_pkey', 'posts', 'posts_author_idx']) AS r;
