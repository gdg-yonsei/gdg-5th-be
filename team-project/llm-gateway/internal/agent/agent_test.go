package agent

// go test ./internal/agent -v

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"example.com/team/llm-gateway/internal/llm"
	"example.com/team/llm-gateway/internal/tools"
)

// fakeTool 은 이름과 결과를 마음대로 정하는 가짜 도구다.
type fakeTool struct {
	name string
	out  string
	err  error
}

func (f fakeTool) Def() llm.ToolDef {
	return llm.ToolDef{Type: "function", Function: llm.Function{Name: f.name}}
}

func (f fakeTool) Call(context.Context, string) (string, error) { return f.out, f.err }

// sent 는 가짜 LLM 이 받은 요청이다.
type sent struct {
	Messages []llm.Message `json:"messages"`
	Tools    []llm.ToolDef `json:"tools"`
}

// fakeLLM 은 정해둔 응답을 순서대로 돌려주는 LLM 서버다. 받은 요청을 기록한다.
// 응답 "tool:이름" = 그 도구 호출, 그 외 = 텍스트. 응답이 모자라면 마지막 걸 반복한다.
func fakeLLM(t *testing.T, replies ...string) (*llm.Client, *[]sent) {
	var reqs []sent
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req sent
		json.NewDecoder(r.Body).Decode(&req)
		reqs = append(reqs, req)

		reply := replies[min(len(reqs), len(replies))-1]
		if name, ok := strings.CutPrefix(reply, "tool:"); ok {
			fmt.Fprintf(w, `data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_%d","function":{"name":%q,"arguments":"{}"}}]}}]}`+"\n\n", len(reqs), name)
		} else {
			fmt.Fprintf(w, `data: {"choices":[{"delta":{"content":%q}}]}`+"\n\n", reply)
		}
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	t.Cleanup(srv.Close) // 테스트가 끝나면 자동으로 서버 종료
	return llm.New(srv.URL, "", "m"), &reqs
}

// collect 는 onText 로 받은 텍스트를 got 에 모은다.
func collect(got *string) func(string) error {
	return func(s string) error { *got += s; return nil }
}

func user(text string) []llm.Message { return []llm.Message{{Role: "user", Content: text}} }

func TestNoTool(t *testing.T) {
	c, reqs := fakeLLM(t, "안녕하세요")
	var got string
	if err := Run(context.Background(), c, user("안녕"), nil, collect(&got)); err != nil || got != "안녕하세요" || len(*reqs) != 1 {
		t.Fatalf("got=%q err=%v calls=%d", got, err, len(*reqs))
	}
}

func TestOneToolRound(t *testing.T) {
	c, reqs := fakeLLM(t, "tool:clock", "2시예요")
	clock := fakeTool{name: "clock", out: `{"time":"14:00"}`}
	var got string
	if err := Run(context.Background(), c, user("몇 시?"), []tools.Tool{clock}, collect(&got)); err != nil || got != "2시예요" || len(*reqs) != 2 {
		t.Fatalf("got=%q err=%v calls=%d", got, err, len(*reqs))
	}
	// 두 번째 요청: [user, assistant(도구 요청), tool(결과)]
	m := (*reqs)[1].Messages
	if len(m) != 3 || m[1].ToolCalls[0].ID != "call_1" || m[2].Role != "tool" || m[2].ToolCallID != "call_1" || m[2].Content != `{"time":"14:00"}` {
		t.Fatalf("2번째 요청 messages = %+v", m)
	}
	if strings.Contains(got, "14:00") {
		t.Error("도구 결과가 사용자에게 그대로 나가면 안 됨")
	}
}

func TestToolErrorGoesToModel(t *testing.T) {
	c, reqs := fakeLLM(t, "tool:broken", "tool:nope", "죄송해요")
	broken := fakeTool{name: "broken", err: errors.New("rag unavailable")}
	if err := Run(context.Background(), c, user("?"), []tools.Tool{broken}, collect(new(string))); err != nil {
		t.Fatal(err)
	}
	m := (*reqs)[2].Messages
	if m[2].Content != `{"error":"rag unavailable"}` || m[4].Content != `{"error":"unknown tool: nope"}` {
		t.Fatalf("도구 에러가 결과로 안 넘어감: %+v", m)
	}
}

func TestTooManyRounds(t *testing.T) {
	c, reqs := fakeLLM(t, "tool:clock") // 계속 도구만 요청하는 모델
	clock := fakeTool{name: "clock", out: "{}"}
	err := Run(context.Background(), c, user("?"), []tools.Tool{clock}, collect(new(string)))
	if !errors.Is(err, ErrTooManyToolRounds) || len(*reqs) != MaxToolRounds+1 {
		t.Fatalf("err=%v calls=%d", err, len(*reqs))
	}
	if (*reqs)[MaxToolRounds].Tools != nil {
		t.Error("마지막 호출엔 도구를 안 줘야 함")
	}
}
