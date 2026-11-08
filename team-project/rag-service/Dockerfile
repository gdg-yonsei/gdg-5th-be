FROM python:3.12-slim
WORKDIR /app
COPY rag-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY proto /proto
RUN python -m grpc_tools.protoc -I /proto --python_out=. --grpc_python_out=. /proto/chat.proto
COPY rag-service/main.py rag-service/memory_consumer.py ./
EXPOSE 8000 50052
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
