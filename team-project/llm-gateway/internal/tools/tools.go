// Package tools 는 모델이 부를 수 있는 도구들이다.
//
// 도구 하나 = 모델에게 보여줄 정의(Def) + 실제로 실행하는 함수(Call).
//
//	t := tools.CurrentTime{}
//	defs := []llm.ToolDef{t.Def()}       // 모델에게 "이런 도구 있어" 알려주기
//	out, err := t.Call(ctx, `{}`)        // 모델이 요청하면 실행 → out 을 모델에게 돌려줌
package tools

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	_ "time/tzdata" // 시간대 DB 를 실행 파일에 포함 (distroless 이미지엔 없어서 LoadLocation 이 실패함)

	pb "example.com/team/llm-gateway/gen"
	"example.com/team/llm-gateway/internal/llm"
)

// Tool 은 모든 도구가 갖춰야 할 메서드다.
type Tool interface {
	// Def 는 모델에게 보낼 도구 정의다.
	Def() llm.ToolDef
	// Call 은 도구를 실행한다. args 는 모델이 만든 JSON 문자열, 반환값은 모델에게 돌려줄 JSON 문자열.
	Call(ctx context.Context, args string) (string, error)
}

// def 는 ToolDef 를 한 줄로 만드는 도우미다. schema 는 인자의 JSON Schema.
func def(name, description, schema string) llm.ToolDef {
	return llm.ToolDef{
		Type:     "function",
		Function: llm.Function{Name: name, Description: description, Parameters: json.RawMessage(schema)},
	}
}

// ---------- get_current_time ----------

// CurrentTime 은 현재 한국 시각을 알려준다. 필드가 필요 없어서 빈 struct.
type CurrentTime struct{}

// Def 는 get_current_time 정의다. 인자는 없다.
func (CurrentTime) Def() llm.ToolDef {
	return def("get_current_time",
		"현재 한국 날짜와 시각을 알려준다. 오늘 날짜, 지금 시간, 요일이 필요할 때 사용한다.",
		`{"type":"object","properties":{}}`)
}

// Call 은 현재 시각을 돌려준다.
// 결과: {"datetime":"2026-11-25T15:04:05+09:00","timezone":"Asia/Seoul"}
func (CurrentTime) Call(ctx context.Context, args string) (string, error) {
	loc, err := time.LoadLocation("Asia/Seoul")
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(map[string]string{
		"datetime": time.Now().In(loc).Format(time.RFC3339),
		"timezone": "Asia/Seoul",
	})
	return string(out), err
}

// ---------- search_memory ----------

// ragTimeout 은 RAG 호출을 기다리는 최대 시간이다.
const ragTimeout = 3 * time.Second

// SearchMemory 는 RAG 서비스에서 사용자에 대한 기억을 찾는다.
// ConversationID 는 모델이 아니라 요청(ChatRequest)에서 받아 넣는다 → 요청마다 새로 만든다.
type SearchMemory struct {
	RAG            pb.RagServiceClient // 생성된 gRPC 클라이언트. 인터페이스라서 테스트 때 가짜를 넣을 수 있다
	ConversationID string
}

// Def 는 search_memory 정의다. 인자는 {"query": "…"}.
func (SearchMemory) Def() llm.ToolDef {
	return def("search_memory",
		"사용자에 대해 이전에 저장된 정보(학교, 취향, 예전에 말한 내용 등)를 검색한다.",
		`{"type":"object","properties":{"query":{"type":"string","description":"찾을 내용"}},"required":["query"]}`)
}

// memoryChunk 는 결과 JSON 의 항목 하나다.
type memoryChunk struct {
	ID      string  `json:"id"`
	Content string  `json:"content"`
	Score   float64 `json:"score"`
}

// Call 은 RAG 에서 기억을 검색한다.
// 결과: {"chunks":[{"id":…,"content":…,"score":…}]}, 결과가 없으면 {"chunks":[]}
func (s SearchMemory) Call(ctx context.Context, args string) (string, error) {
	// 1. 인자 검사. 모델은 틀린 JSON 이나 빈 값을 줄 수도 있다.
	var in struct {
		Query string `json:"query"`
	}
	if err := json.Unmarshal([]byte(args), &in); err != nil {
		return "", fmt.Errorf("invalid arguments: %w", err)
	}
	if strings.TrimSpace(in.Query) == "" {
		return "", errors.New("query is required")
	}

	// 2. RAG 호출. ctx 를 이어받으니 사용자가 끊으면 같이 취소되고, 3초가 넘어도 포기한다.
	ctx, cancel := context.WithTimeout(ctx, ragTimeout)
	defer cancel()
	resp, err := s.RAG.Retrieve(ctx, &pb.RetrieveRequest{ConversationId: s.ConversationID, Query: in.Query})
	if err != nil {
		return "", fmt.Errorf("rag unavailable: %w", err) // "결과 없음"과 구분하려고 에러로 돌려준다
	}

	// 3. JSON 으로 변환. make(…, 0) 이라서 결과가 없으면 null 이 아니라 [] 가 된다.
	chunks := make([]memoryChunk, 0, len(resp.GetChunks()))
	for _, c := range resp.GetChunks() {
		chunks = append(chunks, memoryChunk{ID: c.GetId(), Content: c.GetContent(), Score: c.GetScore()})
	}
	out, err := json.Marshal(map[string]any{"chunks": chunks})
	return string(out), err
}
