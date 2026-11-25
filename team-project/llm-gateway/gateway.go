package main

import (
	"log"

	pb "example.com/team/llm-gateway/gen"
	"example.com/team/llm-gateway/internal/agent"
	"example.com/team/llm-gateway/internal/llm"
	"example.com/team/llm-gateway/internal/tools"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// gateway 는 proto 의 LlmGateway 서비스를 구현한다.
// 모든 요청이 이 값 하나를 같이 쓰므로, 요청마다 달라지는 값은 필드에 두지 않는다.
type gateway struct {
	pb.UnimplementedLlmGatewayServer // 임베딩: 구현 안 한 RPC 는 Unimplemented 에러로 채워준다

	llm *llm.Client
	rag pb.RagServiceClient
}

// Generate 는 Spring 이 부르는 RPC 다. 대화 이력을 받아 답변을 조각조각 스트리밍한다.
//
//	성공: ChatChunk{delta} … ChatChunk{done: true}   ← done 은 딱 한 번
//	실패: gRPC 에러 (done 없음)
func (g *gateway) Generate(req *pb.ChatRequest, stream grpc.ServerStreamingServer[pb.ChatChunk]) error {
	if len(req.GetMessages()) == 0 {
		return status.Error(codes.InvalidArgument, "messages is empty")
	}

	// 1. proto 메시지 → llm 메시지
	msgs := make([]llm.Message, 0, len(req.GetMessages()))
	for _, m := range req.GetMessages() {
		msgs = append(msgs, llm.Message{Role: m.GetRole(), Content: m.GetContent()})
	}

	// 2. 이 요청에서 쓸 도구. conversation_id 는 모델이 아니라 요청에서 넣는다 → 요청마다 새로 만든다
	toolset := []tools.Tool{
		tools.CurrentTime{},
		tools.SearchMemory{RAG: g.rag, ConversationID: req.GetConversationId()},
	}

	// 3. 도구 루프 실행. 답변 텍스트가 나올 때마다 바로 Spring 으로 보낸다.
	//    stream.Context() 는 Spring 이 끊으면 취소된다 → LLM·RAG 요청도 같이 끊긴다
	send := func(text string) error {
		// 클로저;;
		return stream.Send(&pb.ChatChunk{Delta: text})
	}
	if err := agent.Run(stream.Context(), g.llm, msgs, toolset, send); err != nil {
		log.Printf("generate failed: conversation=%s err=%v", req.GetConversationId(), err)
		return status.Error(codes.Unavailable, err.Error())
	}

	// 4. 성공했을 때만 done 을 보낸다
	return stream.Send(&pb.ChatChunk{Done: true})
}
