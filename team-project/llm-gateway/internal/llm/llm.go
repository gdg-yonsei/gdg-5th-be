// Package llm 은 OpenAI 호환 Chat Completions API 를 스트리밍으로 호출한다.
// OpenRouter, Gemini(OpenAI 호환 엔드포인트), mockllm 을 모두 같은 코드로 쓴다.
//
//	c := llm.New(baseURL, apiKey, model)
//	res, err := c.Stream(ctx, msgs, defs, func(text string) error {
//		// 텍스트 조각이 올 때마다 호출된다 (gateway 는 여기서 바로 Spring 에 보낸다)
//		return nil
//	})
//	// 끝나면 res.Content = 전체 텍스트, res.ToolCalls = 모델이 요청한 도구
package llm

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// ---------- 주고받는 타입 (OpenAI 형식 그대로) ----------

// Message 는 대화 한 턴이다.
type Message struct {
	Role       string     `json:"role"` // system | user | assistant | tool
	Content    string     `json:"content"`
	ToolCalls  []ToolCall `json:"tool_calls,omitempty"`   // role=assistant: 모델이 요청한 도구들
	ToolCallID string     `json:"tool_call_id,omitempty"` // role=tool: 어느 요청에 대한 결과인지
}

// ToolCall 은 모델이 요청한 도구 호출 하나다.
type ToolCall struct {
	ID       string       `json:"id"`
	Type     string       `json:"type"` // 항상 "function"
	Function FunctionCall `json:"function"`
	// ExtraContent 는 Gemini 가 붙이는 thought_signature 다.
	// 다음 요청에 받은 그대로 돌려줘야 400 에러가 안 난다. 없으면(OpenRouter) 안 보낸다.
	ExtraContent json.RawMessage `json:"extra_content,omitempty"`
}

// FunctionCall 은 호출할 함수 이름과 인자다.
type FunctionCall struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"` // JSON "문자열". 예: `{"query":"학교"}`
}

// ToolDef 는 모델에게 "이런 도구가 있다"고 알려주는 정의다.
type ToolDef struct {
	Type     string   `json:"type"` // 항상 "function"
	Function Function `json:"function"`
}

// Function 은 도구의 이름·설명·인자 형식이다. 모델은 Description 을 보고 언제 부를지 정한다.
type Function struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	Parameters  json.RawMessage `json:"parameters"` // JSON Schema 를 그대로
}

// Result 는 응답 하나를 끝까지 읽은 결과다. ToolCalls 가 비어 있으면 최종 답변이다.
type Result struct {
	Content   string
	ToolCalls []ToolCall
}

// request 는 API 요청 본문이다.
type request struct {
	Model    string    `json:"model"`
	Messages []Message `json:"messages"`
	Tools    []ToolDef `json:"tools,omitempty"`
	Stream   bool      `json:"stream"`
}

// ---------- Client ----------

// Client 는 LLM API 접속 정보를 들고 있다. 여러 고루틴이 같이 써도 안전하다 (상태를 안 바꿈).
type Client struct {
	baseURL string // 예: "https://openrouter.ai/api/v1"
	apiKey  string
	model   string
}

// New 는 Client 를 만든다.
func New(baseURL, apiKey, model string) *Client {
	return &Client{baseURL: baseURL, apiKey: apiKey, model: model}
}

// Stream 은 모델을 한 번 호출한다. 텍스트 조각이 올 때마다 onText 를 부르고,
// 응답이 끝나면 모은 결과를 돌려준다. ctx 가 취소되면 HTTP 연결도 바로 끊긴다.
func (c *Client) Stream(ctx context.Context, msgs []Message, tools []ToolDef, onText func(string) error) (Result, error) {
	// 1. 요청 만들기
	body, err := json.Marshal(request{Model: c.model, Messages: msgs, Tools: tools, Stream: true})
	if err != nil {
		return Result{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.apiKey)

	// 2. 보내기. 응답 헤더까지만 받고 돌아온다 (본문은 계속 흘러 들어오는 중)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return Result{}, fmt.Errorf("llm: %w", err)
	}
	defer resp.Body.Close()

	// 3. 200 이 아니면 스트림이 시작도 안 된 것. 본문 앞부분을 에러에 담는다 (429, 401 등)
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return Result{}, fmt.Errorf("llm: http %d: %s", resp.StatusCode, msg)
	}

	// 4. 본문(SSE) 을 끝까지 읽는다
	return readStream(resp.Body, onText)
}

// ---------- SSE 읽기 ----------

// chunk 는 SSE "data:" 줄 하나의 JSON 이다. 필요한 필드만 정의했다.
type chunk struct {
	Choices []struct {
		Delta struct {
			Content   string `json:"content"`
			ToolCalls []struct {
				Index    *int `json:"index"` // 포인터: 필드가 없으면 nil (Gemini 는 안 보냄)
				ToolCall      // 임베딩: id·type·function·extra_content 를 그대로 받는다
			} `json:"tool_calls"`
		} `json:"delta"`
	} `json:"choices"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"` // 스트림 도중 에러는 HTTP 200 안에 이렇게 온다
}

// readStream 은 SSE 본문을 한 줄씩 읽는다.
//
//	: OPENROUTER PROCESSING                        ← 주석. 무시
//	data: {"choices":[{"delta":{"content":"안"}}]}  ← 조각
//	data: [DONE]                                   ← 끝
func readStream(body io.Reader, onText func(string) error) (Result, error) {
	var res Result
	sc := bufio.NewScanner(body)
	for sc.Scan() { // 한 줄이 다 올 때까지 기다렸다가 true. 연결이 끝나거나 에러면 false
		data, ok := strings.CutPrefix(sc.Text(), "data: ")
		if !ok {
			continue // 주석, 빈 줄
		}
		if data == "[DONE]" {
			return res, nil
		}

		var c chunk
		if err := json.Unmarshal([]byte(data), &c); err != nil {
			return res, fmt.Errorf("llm: bad chunk: %w", err)
		}
		if c.Error != nil {
			return res, errors.New("llm: " + c.Error.Message)
		}
		if len(c.Choices) == 0 {
			continue
		}
		delta := c.Choices[0].Delta

		// 텍스트 조각: 모아두고 바로 내보낸다
		if delta.Content != "" {
			res.Content += delta.Content
			if err := onText(delta.Content); err != nil {
				return res, err
			}
		}
		// 도구 호출 조각: 모아서 완성한다
		for _, d := range delta.ToolCalls {
			res.addToolCall(d.Index, d.ToolCall)
		}
	}
	if err := sc.Err(); err != nil {
		return res, fmt.Errorf("llm: read: %w", err) // ctx 취소도 여기로 온다
	}
	return res, errors.New("llm: stream ended without [DONE]")
}

// addToolCall 은 도구 호출 조각 하나를 Result 에 합친다.
//
//	OpenAI·OpenRouter: 한 호출이 여러 조각으로 온다 → index 자리에 이어붙인다
//	                   {index:0, id, name} → {index:0, arguments:"{\"q"} → {index:0, arguments:"…\"}"}
//	Gemini:            조각 하나가 완성된 호출 하나, index 없음 → 맨 뒤에 새로 추가한다
func (r *Result) addToolCall(index *int, part ToolCall) {
	i := len(r.ToolCalls)
	if index != nil {
		i = *index
	}
	for len(r.ToolCalls) <= i {
		r.ToolCalls = append(r.ToolCalls, ToolCall{Type: "function"})
	}

	tc := &r.ToolCalls[i] // 포인터로 받아야 슬라이스 안의 값이 바뀐다
	if part.ID != "" {
		tc.ID = part.ID
	}
	if part.Function.Name != "" {
		tc.Function.Name = part.Function.Name
	}
	tc.Function.Arguments += part.Function.Arguments
	if part.ExtraContent != nil {
		tc.ExtraContent = part.ExtraContent
	}
}
