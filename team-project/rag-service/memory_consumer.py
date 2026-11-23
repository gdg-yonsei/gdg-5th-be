"""팀원 4: 래빗엠큐 이벤트를 수신하고 팀원 3의 메모리 저장 기능을 호출하세요."""
import asyncio
import logging
import os
from datetime import datetime
from typing import Literal
from uuid import UUID

import aio_pika
from pydantic import BaseModel, Field, ValidationError

logger = logging.getLogger("uvicorn.error")
QUEUE_NAME = "memory.add.requested.v1"

class MemoryRequestedEvent(BaseModel):
    schemaVersion: Literal[1]
    type: Literal["memory.add.requested"]
    eventId: UUID
    occurredAt: datetime
    conversationId: str = Field(min_length=1)
    userMessage: str
    assistantMessage: str

async def handle_memory(event: MemoryRequestedEvent):
    # TODO (팀원 3): 이벤트 식별자로 중복을 처리하고 메모리와 문서 조각을 데이터베이스에 저장하세요.
    pass

async def consume_memory():
    while True:
        try:
            connection = await aio_pika.connect_robust(
                host=os.getenv("RABBITMQ_HOST", "localhost"),
                port=int(os.getenv("RABBITMQ_PORT", "5672")),
                login=os.getenv("RABBITMQ_USERNAME", "gdgoc"),
                password=os.getenv("RABBITMQ_PASSWORD", "gdgoc-dev-password"),
                virtualhost="/",
                timeout=5,
            )
            async with connection:
                channel = await connection.channel()
                await channel.set_qos(prefetch_count=1)
                # 큐는 rabbitmq-init이 생성합니다. 초기화 전이라면 아래에서 재연결합니다.
                queue = await channel.get_queue(QUEUE_NAME)
                logger.info("Consuming memory events from %s", QUEUE_NAME)
                async with queue.iterator() as messages:
                    async for message in messages:
                        await process_message(message)
        except asyncio.CancelledError:
            # 연결 종료 시 ACK하지 않은 메시지는 브로커가 다시 큐에 넣습니다.
            raise
        except Exception:
            logger.exception("Memory consumer disconnected; retrying in 5 seconds")
            await asyncio.sleep(5)


async def process_message(message: aio_pika.IncomingMessage):
    try:
        event = MemoryRequestedEvent.model_validate_json(message.body)
    except ValidationError:
        logger.exception("Rejecting invalid memory event")
        await message.reject(requeue=False)
        return

    logger.info("Received memory event: %s", event.model_dump_json())

    for attempt in range(1, 4):
        try:
            await handle_memory(event)
        except Exception:
            logger.exception("Memory event %s failed (attempt %s/3)", event.eventId, attempt)
            if attempt == 3:
                await message.nack(requeue=False)
                return
            await asyncio.sleep(attempt)
        else:
            await message.ack()
            return
