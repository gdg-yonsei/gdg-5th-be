"""팀원 3: 샘플 응답을 실제 메모리 검색과 저장으로 교체하세요."""
from contextlib import asynccontextmanager
from contextlib import suppress
import asyncio
import logging

import grpc
from fastapi import FastAPI
from pydantic import BaseModel, Field

import chat_pb2 as pb
import chat_pb2_grpc as rpc
from memory_consumer import consume_memory

logger = logging.getLogger("uvicorn.error")

SAMPLE_MEMORY = {
    "id": "sample-memory-1",
    "content": "유저는 현재 연세대학교에 재학중이다.",
    "score": 1.0,
}

class RagService(rpc.RagServiceServicer):
    async def Retrieve(self, request, context):
        # TODO (팀원 3): 데이터베이스에서 대화별 메모리와 관련 문서 조각을 조회하세요.
        return pb.RetrieveResponse(chunks=[pb.ContextChunk(**SAMPLE_MEMORY)])

    async def StoreMemory(self, request, context):
        # TODO (팀원 3): 답변이 완료되면 메모리를 분할하고 임베딩을 생성해 데이터베이스에 저장하세요.
        logger.info("Mock StoreMemory called (no persistence)")
        return pb.StoreMemoryResponse(stored=False)

@asynccontextmanager
async def lifespan(app: FastAPI):
    server = grpc.aio.server()
    rpc.add_RagServiceServicer_to_server(RagService(), server)
    if server.add_insecure_port("[::]:50052") == 0:
        raise RuntimeError("Could not bind gRPC port 50052")
    await server.start()
    app.state.grpc_server = server
    consumer_task = asyncio.create_task(consume_memory())
    try:
        yield
    finally:
        consumer_task.cancel()
        with suppress(asyncio.CancelledError):
            await consumer_task
        await server.stop(grace=3)

app = FastAPI(title="RAG Service · Mock", version="0.1.0", lifespan=lifespan)

class RetrieveRequest(BaseModel):
    conversation_id: str = Field(min_length=1)
    query: str = Field(min_length=1)

class MemoryRequest(BaseModel):
    conversation_id: str = Field(min_length=1)
    user_message: str
    assistant_message: str

@app.get("/health")
async def health():
    return {"status": "ok", "mode": "mock"}

@app.post("/retrieve")
async def retrieve(request: RetrieveRequest):
    return {"chunks": [SAMPLE_MEMORY]}

@app.post("/memories")
async def store_memory(request: MemoryRequest):
    return None
