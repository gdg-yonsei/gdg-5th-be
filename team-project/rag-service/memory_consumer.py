"""팀원 4: 래빗엠큐 이벤트를 수신하고 팀원 3의 메모리 저장 기능을 호출하세요."""
from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field

class MemoryRequestedEvent(BaseModel):
    schemaVersion: Literal[1]
    type: Literal["memory.add.requested"]
    eventId: UUID
    occurredAt: datetime
    conversationId: str = Field(min_length=1)
    userMessage: str
    assistantMessage: str

async def handle_memory(event: MemoryRequestedEvent):
    # TODO (팀원 3): 이벤트 식별자로 중복을 처리하고 메모리 분할, 임베딩 생성 및 저장을 구현하세요.
    pass

async def consume_memory():
    # TODO (팀원 4): 비동기 메시징 라이브러리로 큐를 구독하고 메시지 본문을 이벤트 자료형으로 변환하세요.
    # TODO (팀원 4): 메모리 저장 함수 호출, 수신 확인과 거부, 재시도 및 종료 처리를 구현하세요.
    # TODO (팀원 4): 애플리케이션 수명 주기에 메시지 수신기의 시작과 종료를 연결하세요.
    pass
