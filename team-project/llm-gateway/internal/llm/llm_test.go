package llm

// go test ./internal/llm -v

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func noop(string) error { return nil }

func TestText(t *testing.T) {
	body := `: OPENROUTER PROCESSING

data: {"choices":[{"delta":{"content":"안녕"}}]}

data: {"choices":[{"delta":{"content":"하세요"}}]}

data: [DONE]
`
	var got []string
	res, err := readStream(strings.NewReader(body), func(s string) error {
		got = append(got, s)
		return nil
	})
	if err != nil || res.Content != "안녕하세요" || len(got) != 2 {
		t.Fatalf("res=%+v got=%q err=%v", res, got, err)
	}
}

func TestToolCallPieces(t *testing.T) {
	// OpenAI 형식: arguments 가 여러 조각으로 나뉘어 온다
	body := `data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"search_memory","arguments":""}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"학교\"}"}}]}}]}
data: [DONE]
`
	res, err := readStream(strings.NewReader(body), noop)
	if err != nil || len(res.ToolCalls) != 1 {
		t.Fatalf("res=%+v err=%v", res, err)
	}
	tc := res.ToolCalls[0]
	if tc.ID != "call_1" || tc.Function.Name != "search_memory" || tc.Function.Arguments != `{"query":"학교"}` {
		t.Errorf("합친 결과 = %+v", tc)
	}
}

func TestToolCallsWithoutIndex(t *testing.T) {
	// Gemini 형식: index 없이 완성된 호출이 하나씩, thought_signature 가 붙어 온다
	body := `data: {"choices":[{"delta":{"tool_calls":[{"extra_content":{"google":{"thought_signature":"sig"}},"function":{"arguments":"{}","name":"get_current_time"},"id":"a","type":"function"}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"query\":\"학교\"}","name":"search_memory"},"id":"b","type":"function"}]}}]}
data: [DONE]
`
	res, err := readStream(strings.NewReader(body), noop)
	if err != nil || len(res.ToolCalls) != 2 {
		t.Fatalf("res=%+v err=%v", res, err)
	}
	a, b := res.ToolCalls[0], res.ToolCalls[1]
	if a.Function.Name != "get_current_time" || a.Function.Arguments != "{}" || b.ID != "b" || b.Function.Arguments != `{"query":"학교"}` {
		t.Errorf("섞임: %+v / %+v", a, b)
	}
	if string(a.ExtraContent) != `{"google":{"thought_signature":"sig"}}` || b.ExtraContent != nil {
		t.Errorf("extra_content = %s / %s", a.ExtraContent, b.ExtraContent)
	}
}

func TestStreamCut(t *testing.T) {
	body := "data: {\"choices\":[{\"delta\":{\"content\":\"중간\"}}]}\n" // [DONE] 없이 끝남
	if _, err := readStream(strings.NewReader(body), noop); err == nil {
		t.Fatal("끊긴 스트림은 에러여야 함")
	}
}

func TestErrorInStream(t *testing.T) {
	body := `data: {"error":{"message":"overloaded"}}` + "\n"
	if _, err := readStream(strings.NewReader(body), noop); err == nil || !strings.Contains(err.Error(), "overloaded") {
		t.Fatalf("err=%v", err)
	}
}

func TestHTTP(t *testing.T) {
	// httptest: 테스트용 HTTP 서버를 잠깐 띄운다
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer key" {
			http.Error(w, "no auth", http.StatusUnauthorized)
			return
		}
		io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
	}))
	defer srv.Close()

	ctx := context.Background()
	if res, err := New(srv.URL, "key", "m").Stream(ctx, nil, nil, noop); err != nil || res.Content != "ok" {
		t.Fatalf("정상 요청: res=%+v err=%v", res, err)
	}
	if _, err := New(srv.URL, "wrong", "m").Stream(ctx, nil, nil, noop); err == nil || !strings.Contains(err.Error(), "401") {
		t.Fatalf("401 에러 기대, err=%v", err)
	}
}
