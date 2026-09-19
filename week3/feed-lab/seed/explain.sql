-- v1 피드 쿼리를 헤비(A, id=1) / 라이트(B, id=2) 로 실행 계획과 함께 본다.
\set q 'SELECT p.id, p.author_id, p.content, p.created_at FROM follows f JOIN LATERAL (SELECT id, author_id, content, created_at FROM posts WHERE author_id = f.followee_id AND (created_at, id) < (now(), 9223372036854775807) ORDER BY created_at DESC, id DESC LIMIT 20) p ON true WHERE f.follower_id = :me ORDER BY p.created_at DESC, p.id DESC LIMIT 20'
\echo '=================== A · 헤비 (3,000명 팔로우) ==================='
\set me 1
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) :q;
\echo '=================== B · 라이트 (20명 팔로우) ===================='
\set me 2
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) :q;
