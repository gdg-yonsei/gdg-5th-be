"""팀원 3: 샘플 응답을 실제 메모리 검색과 저장으로 교체하세요."""
from contextlib import asynccontextmanager
import logging

import grpc
from fastapi import FastAPI
from pydantic import BaseModel, Field

import chat_pb2 as pb
import chat_pb2_grpc as rpc

logger = logging.getLogger("uvicorn.error")

SAMPLE_MEMORY = {
    "id": "sample-memory-1",
    "content": "유저는 현재 연세대학교에 재학중이다.",
    "score": 1.0,
}

class RagService(rpc.RagServiceServicer):
    async def Retrieve(self, request, context):
        # TODO (팀원 3): 대화의 메모리 저장소를 검색하고 관련 문서 조각을 반환하세요.
        return pb.RetrieveResponse(chunks=[pb.ContextChunk(**SAMPLE_MEMORY)])

    async def StoreMemory(self, request, context):
        # TODO (팀원 3): 답변이 완료될 때마다 메모리를 분할하고 임베딩을 생성해 저장하세요.
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
    # TODO (팀원 4): 애플리케이션 수명 주기에 래빗엠큐 메시지 수신기의 시작과 종료를 연결하세요.
    try:
        yield
    finally:
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
