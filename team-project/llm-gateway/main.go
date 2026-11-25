// llm-gateway 는 Spring 에게 gRPC Generate 를 제공하고, 뒤에서 LLM·RAG 를 호출하는 서버다.
//
//	Spring ──gRPC──▶ gateway(:50051) ──HTTP/SSE──▶ LLM (OpenRouter·Gemini·mockllm)
//	                                 └──gRPC─────▶ RAG (search_memory 도구)
//
// 환경변수
//
//	LLM_BASE_URL  OpenAI 호환 API 주소 (기본: 로컬 mockllm http://localhost:8090/v1)
//	LLM_API_KEY   API 키
//	LLM_MODEL     모델 이름
//	RAG_ADDR      RAG gRPC 주소 (기본: localhost:50052)
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	pb "example.com/team/llm-gateway/gen"
	"example.com/team/llm-gateway/internal/llm"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
)

const port = ":50051"

func main() {
	// -healthcheck: 서버를 띄우지 않고 "떠 있는 서버가 건강한지"만 확인하고 끝낸다.
	// distroless 이미지엔 셸·curl 이 없어서 docker healthcheck 가 이 바이너리 자신을 쓴다.
	healthcheck := flag.Bool("healthcheck", false, "check the running server's health and exit")
	flag.Parse()

	var err error
	if *healthcheck {
		err = checkHealth()
	} else {
		err = run()
	}
	if err != nil {
		log.Fatal(err) // 에러 출력 후 exit 1
	}
}

// run 은 서버를 띄우고, 종료 신호가 오면 진행 중인 답변을 마저 보낸 뒤 돌아온다.
func run() error {
	llmURL := getenv("LLM_BASE_URL", "http://localhost:8090/v1")
	model := os.Getenv("LLM_MODEL")
	ragAddr := getenv("RAG_ADDR", "localhost:50052")

	// 1. RAG 클라이언트. NewClient 는 바로 연결하지 않는다 (처음 호출할 때 연결) → RAG 가 꺼져 있어도 시작은 된다
	ragConn, err := grpc.NewClient(ragAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return err
	}
	defer ragConn.Close()

	// 2. gRPC 서버에 서비스 등록
	server := grpc.NewServer()
	pb.RegisterLlmGatewayServer(server, &gateway{
		llm: llm.New(llmURL, os.Getenv("LLM_API_KEY"), model),
		rag: pb.NewRagServiceClient(ragConn),
	})
	healthpb.RegisterHealthServer(server, health.NewServer()) // 기본 상태가 SERVING

	// 3. 포트 열기
	lis, err := net.Listen("tcp", port)
	if err != nil {
		return err
	}

	// 4. Ctrl+C 나 docker stop(SIGTERM) 이 오면 ctx 가 취소된다 → GracefulStop
	//    GracefulStop: 새 요청은 거절하고, 진행 중인 스트림이 끝날 때까지 기다린다
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		log.Println("shutting down")
		server.GracefulStop()
	}()

	// 5. 요청 받기 시작. GracefulStop 이 끝나야 리턴한다
	log.Printf("listening on %s (llm=%s model=%q rag=%s)", port, llmURL, model, ragAddr)
	return server.Serve(lis)
}

// checkHealth 는 로컬 서버의 gRPC health 서비스를 호출한다.
func checkHealth() error {
	conn, err := grpc.NewClient("localhost"+port, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return err
	}
	defer conn.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	res, err := healthpb.NewHealthClient(conn).Check(ctx, &healthpb.HealthCheckRequest{})
	if err != nil {
		return err
	}
	if res.GetStatus() != healthpb.HealthCheckResponse_SERVING {
		return fmt.Errorf("not serving: %s", res.GetStatus())
	}
	return nil
}

// getenv 는 환경변수를 읽고, 비어 있으면 기본값을 쓴다.
func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
