FROM golang:1.23-bookworm AS build
RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler && rm -rf /var/lib/apt/lists/*
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.5 && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
WORKDIR /workspace/llm-gateway
COPY llm-gateway/ ./
COPY proto /workspace/proto
RUN mkdir -p gen && protoc -I /workspace/proto --go_out=gen --go_opt=paths=source_relative --go-grpc_out=gen --go-grpc_opt=paths=source_relative /workspace/proto/chat.proto
RUN go mod tidy && CGO_ENABLED=0 go build -o /gateway .
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /gateway /gateway
EXPOSE 50051
ENTRYPOINT ["/gateway"]
