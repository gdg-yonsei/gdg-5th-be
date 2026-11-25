// Package agent 는 도구 루프다. 모델이 도구를 요청하면 실행하고, 결과를 붙여서 다시 부른다.
//
//	1회차: [user]                             → 모델: "get_current_time 불러줘"
//	       도구 실행
//	2회차: [user, assistant(요청), tool(결과)] → 모델: "지금은 오후 2시예요" (텍스트 = 끝)
package agent

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"time"

	"example.com/team/llm-gateway/internal/llm"
	"example.com/team/llm-gateway/internal/tools"
)

// MaxToolRounds 는 도구 실행 최대 횟수다. 그 다음 호출엔 도구를 안 줘서 텍스트 답변을 강제한다.
const MaxToolRounds = 5

// ErrTooManyToolRounds 는 도구를 빼고 불러도 모델이 계속 도구를 요청할 때의 에러다.
var ErrTooManyToolRounds = errors.New("agent: too many tool rounds")

// Run 은 최종 답변이 나올 때까지 모델을 부른다. 답변 텍스트는 onText 로 바로 흘려보낸다.
// 도구 인자·결과는 msgs 안에서만 오가고 onText 로는 안 나간다 → 사용자에게 안 보인다.
func Run(ctx context.Context, c *llm.Client, msgs []llm.Message, toolset []tools.Tool, onText func(string) error) error {
	// 모델에게 보낼 정의 목록과, 이름으로 찾을 맵을 준비
	defs := make([]llm.ToolDef, 0, len(toolset))
	byName := make(map[string]tools.Tool, len(toolset))
	for _, t := range toolset {
		d := t.Def()
		defs = append(defs, d)
		byName[d.Function.Name] = t
	}

	for round := 0; ; round++ {
		last := round == MaxToolRounds
		offered := defs
		if last {
			offered = nil // 마지막 기회: 도구 없이 답해라
		}

		// 1. 모델 호출. 텍스트는 여기서 이미 사용자에게 나간다.
		res, err := c.Stream(ctx, msgs, offered, onText)
		if err != nil {
			return err
		}

		// 2. 도구 요청이 없으면 = 최종 답변이 끝난 것
		if len(res.ToolCalls) == 0 {
			return nil
		}
		if last {
			return ErrTooManyToolRounds
		}

		// 3. "모델이 이 도구들을 요청했었다"를 기록. 모델은 이전 대화를 기억 못 하니 매번 전부 다시 보낸다.
		msgs = append(msgs, llm.Message{Role: "assistant", Content: res.Content, ToolCalls: res.ToolCalls})

		// 4. 도구를 실행하고 결과를 tool 메시지로 붙인다. ToolCallID 로 어느 요청의 결과인지 짝짓는다.
		for _, tc := range res.ToolCalls {
			out := execute(ctx, byName, tc, round+1)
			msgs = append(msgs, llm.Message{Role: "tool", ToolCallID: tc.ID, Content: out})
		}
	}
}

// execute 는 도구 하나를 실행한다. 실패해도 루프를 멈추지 않고 {"error":"…"} 를 결과로 돌려준다.
// → 모델이 그걸 읽고 "지금은 기억을 불러올 수 없어요"처럼 알아서 대처한다.
func execute(ctx context.Context, byName map[string]tools.Tool, tc llm.ToolCall, round int) string {
	name := tc.Function.Name
	t, ok := byName[name]
	if !ok {
		log.Printf("tool round=%d name=%s: unknown tool", round, name)
		return errorJSON("unknown tool: " + name)
	}

	start := time.Now()
	out, err := t.Call(ctx, tc.Function.Arguments)
	// 인자·결과는 개인정보가 있을 수 있어서 로그에 안 남긴다
	log.Printf("tool round=%d name=%s dur=%v err=%v", round, name, time.Since(start).Round(time.Millisecond), err)
	if err != nil {
		return errorJSON(err.Error())
	}
	return out
}

func errorJSON(msg string) string {
	b, _ := json.Marshal(map[string]string{"error": msg}) // string 맵은 실패할 일이 없다
	return string(b)
}
