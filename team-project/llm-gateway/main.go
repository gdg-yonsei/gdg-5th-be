package main

import (
	"context"
	"flag"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	pb "example.com/team/llm-gateway/gen"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
)

type gateway struct {
	pb.UnimplementedLlmGatewayServer
}

func (g *gateway) Generate(_ *pb.ChatRequest, stream grpc.ServerStreamingServer[pb.ChatChunk]) error {
	// TODO (팀원 2): 대규모 언어 모델 제공자, 도구 실행 및 검색 증강 생성 서비스를 연결하세요.
	// 다른 서비스를 호출하지 않고 고정 응답만 반환하는 독립 모의 구현입니다.
	if err := stream.Send(&pb.ChatChunk{Delta: "안녕하세요"}); err != nil {
		return err
	}
	return stream.Send(&pb.ChatChunk{Done: true})
}

func main() {
	check := flag.Bool("healthcheck", false, "check local gRPC health")
	flag.Parse()
	if *check {
		conn, err := grpc.NewClient("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err != nil {
			log.Fatal(err)
		}
		defer conn.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		result, err := healthpb.NewHealthClient(conn).Check(ctx, &healthpb.HealthCheckRequest{})
		if err != nil || result.GetStatus() != healthpb.HealthCheckResponse_SERVING {
			os.Exit(1)
		}
		return
	}
	listener, err := net.Listen("tcp", ":50051")
	if err != nil {
		log.Fatal(err)
	}
	server := grpc.NewServer()
	pb.RegisterLlmGatewayServer(server, &gateway{})
	healthServer := health.NewServer()
	healthServer.SetServingStatus("", healthpb.HealthCheckResponse_SERVING)
	healthpb.RegisterHealthServer(server, healthServer)
	shutdown := make(chan os.Signal, 1)
	signal.Notify(shutdown, syscall.SIGTERM, syscall.SIGINT)
	go func() { <-shutdown; server.Stop() }()
	log.Print("LLM gateway listening on :50051 (mock provider)")
	if err := server.Serve(listener); err != nil {
		log.Fatal(err)
	}
}
