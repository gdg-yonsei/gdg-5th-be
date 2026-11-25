package tools

// go test ./internal/tools -v

import (
	"context"
	"errors"
	"strings"
	"testing"

	pb "example.com/team/llm-gateway/gen"
	"google.golang.org/grpc"
)

func TestCurrentTime(t *testing.T) {
	out, err := CurrentTime{}.Call(context.Background(), `{}`)
	if err != nil || !strings.Contains(out, "+09:00") || !strings.Contains(out, `"timezone":"Asia/Seoul"`) {
		t.Fatalf("out=%s err=%v", out, err)
	}
}

// fakeRAG 는 가짜 RAG 클라이언트다.
// pb.RagServiceClient 를 임베딩하면 인터페이스를 만족하게 되고, 필요한 Retrieve 만 덮어쓴다.
// (구현 안 한 StoreMemory 를 부르면 패닉 → 테스트에서만 쓰는 기법)
type fakeRAG struct {
	pb.RagServiceClient
	chunks []*pb.ContextChunk
	err    error
	got    *pb.RetrieveRequest // 무엇을 받았는지 기록
}

func (f *fakeRAG) Retrieve(_ context.Context, in *pb.RetrieveRequest, _ ...grpc.CallOption) (*pb.RetrieveResponse, error) {
	f.got = in
	return &pb.RetrieveResponse{Chunks: f.chunks}, f.err
}

func TestSearchMemory(t *testing.T) {
	rag := &fakeRAG{chunks: []*pb.ContextChunk{{Id: "m1", Content: "연세대학교", Score: 1}}}
	out, err := SearchMemory{RAG: rag, ConversationID: "c1"}.Call(context.Background(), `{"query":"학교"}`)
	if err != nil || out != `{"chunks":[{"id":"m1","content":"연세대학교","score":1}]}` {
		t.Fatalf("out=%s err=%v", out, err)
	}
	if rag.got.GetConversationId() != "c1" || rag.got.GetQuery() != "학교" {
		t.Errorf("RAG 가 받은 요청 = %v", rag.got)
	}
}

func TestSearchMemoryEmpty(t *testing.T) {
	out, err := SearchMemory{RAG: &fakeRAG{}}.Call(context.Background(), `{"query":"학교"}`)
	if err != nil || out != `{"chunks":[]}` { // null 이면 안 됨
		t.Fatalf("out=%s err=%v", out, err)
	}
}

func TestSearchMemoryBadArgs(t *testing.T) {
	s := SearchMemory{RAG: &fakeRAG{}}
	for _, args := range []string{`{"query":"  "}`, `{}`, `not json`} {
		if _, err := s.Call(context.Background(), args); err == nil {
			t.Errorf("args=%s: 에러여야 함", args)
		}
	}
}

func TestSearchMemoryRAGDown(t *testing.T) {
	s := SearchMemory{RAG: &fakeRAG{err: errors.New("connection refused")}}
	if _, err := s.Call(context.Background(), `{"query":"학교"}`); err == nil {
		t.Fatal("RAG 실패는 빈 결과가 아니라 에러여야 함")
	}
}
