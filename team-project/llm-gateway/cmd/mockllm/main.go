// mockllm 은 OpenAI 호환 API 를 흉내 내는 가짜 LLM 서버다. 돈 안 쓰고 gateway 를 테스트하려고 쓴다.
//
//	go run ./cmd/mockllm
//	curl -N localhost:8090/v1/chat/completions -d '{"messages":[{"role":"user","content":"안녕"}]}'
//
// 답하는 규칙 (마지막 메시지 기준)
//   - "몇 시" 포함 → get_current_time 도구 호출
//   - "학교" 포함 → search_memory 도구 호출
//   - 도구 결과(role=tool) → 그 결과를 그대로 읽어주는 텍스트
//   - 그 외 → 고정 인사
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

const port = ":8090"

// request 는 요청에서 쓰는 필드만 정의한다. 나머지(model, tools 등)는 무시된다.
type request struct {
	Messages []struct {
		Role    string `json:"role"`
		Content string `json:"content"`
	} `json:"messages"`
}

// M 은 JSON 객체를 짧게 쓰기 위한 별칭이다. M{"a": 1} == map[string]any{"a": 1}
type M = map[string]any

func main() {
	http.HandleFunc("POST /v1/chat/completions", handle)
	log.Println("mockllm listening on " + port)
	log.Fatal(http.ListenAndServe(port, nil))
}

func handle(w http.ResponseWriter, r *http.Request) {
	// 1. 요청 JSON 읽기
	var req request
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.Messages) == 0 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	last := req.Messages[len(req.Messages)-1]

	// 2. SSE 응답 준비. send 는 JSON 하나를 "data: …\n\n" 으로 쓰고 바로 Flush 한다
	//    (Flush 안 하면 버퍼에 모였다가 한꺼번에 나가서 스트리밍이 안 됨)
	w.Header().Set("Content-Type", "text/event-stream")
	flusher := w.(http.Flusher)
	send := func(v any) {
		b, _ := json.Marshal(v)
		fmt.Fprintf(w, "data: %s\n\n", b)
		flusher.Flush()
	}

	// 3. 도구 호출로 답하거나, 텍스트를 단어 단위로 흘려보낸다
	switch {
	case last.Role != "tool" && strings.Contains(last.Content, "몇 시"):
		send(toolCall("get_current_time", `{}`))
	case last.Role != "tool" && strings.Contains(last.Content, "학교"):
		send(toolCall("search_memory", `{"query":"사용자의 학교"}`))
	default:
		text := "(mock) 안녕하세요! 무엇을 도와드릴까요?"
		if last.Role == "tool" {
			text = "(mock) 도구 결과: " + last.Content
		}
		for _, word := range strings.Fields(text) {
			// 진짜 LLM 처럼 100ms 간격. 그 사이 클라이언트가 끊으면(ctx 취소) 바로 멈춘다
			select {
			case <-time.After(100 * time.Millisecond):
			case <-r.Context().Done():
				log.Println("client gone")
				return
			}
			send(M{"choices": []M{{"delta": M{"content": word + " "}}}})
		}
	}

	// 4. 끝 표시
	fmt.Fprint(w, "data: [DONE]\n\n")
	flusher.Flush()
}

// toolCall 은 도구 호출 청크를 만든다. (진짜 API 는 이걸 여러 조각으로 쪼개 보내기도 한다)
func toolCall(name, args string) M {
	call := M{"index": 0, "id": "call_1", "type": "function",
		"function": M{"name": name, "arguments": args}}
	return M{"choices": []M{{"delta": M{"tool_calls": []M{call}}}}}
}
