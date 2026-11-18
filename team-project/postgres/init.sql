-- 팀원 3이 메모리 저장과 검색을 구현할 때 사용할 초기 테이블입니다.
-- 새로운 데이터 볼륨의 첫 실행에서만 자동 적용됩니다.

CREATE TABLE IF NOT EXISTS memory_events (
    event_id UUID PRIMARY KEY,
    conversation_id TEXT NOT NULL CHECK (length(conversation_id) > 0),
    user_message TEXT NOT NULL,
    assistant_message TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    stored_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS memory_events_conversation_idx
    ON memory_events (conversation_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS memory_chunks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES memory_events (event_id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL CHECK (length(btrim(content)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (event_id, chunk_index)
);

-- TODO (팀원 3): 이벤트 중복 처리와 문서 조각 저장을 하나의 트랜잭션으로 구현하세요.
-- TODO (팀원 3): 검색 시 이벤트 테이블과 연결하고 대화 식별자로 조회 범위를 제한하세요.
-- TODO (팀원 3): 임베딩 기반 검색을 구현할 때 벡터 저장 방식과 인덱스를 추가하세요.

-- TODO (팀원 5): 채팅 목록과 원문 메시지를 보관할 대화 및 메시지 테이블을 설계하세요.
-- TODO (팀원 5): 대화별 메시지 순서, 최근 갱신 시각 및 메시지 중복 저장 방식을 정의하세요.
-- 위 메모리 테이블은 팀원 3의 검색용 저장소이며 채팅 내역 테이블은 별도로 설계하세요.
