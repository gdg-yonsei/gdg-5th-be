# LLM Gateway 스펙

팀 README의 "팀원 2" 명세와 확장 목표를 정리한 문서다. 각 항목은 **현재 상태 / 최소 구현 / 함정 / 완료 확인** 순서로 적는다.

```
Spring ──gRPC Generate(stream ChatChunk)──▶ Gateway ──HTTPS SSE (OpenAI 호환)──▶ Gemini · OpenRouter (또는 mock-llm)
                                              │
                                              └─ search_memory 도구 ──gRPC Retrieve──▶ RAG
```

- 고정 계약: `Generate`, `Retrieve` proto는 바꾸지 않는다. 도구 실행은 gateway 내부 일이고, 사용자에게는 최종 답변 `delta`만 나간다. `done=true`는 성공했을 때 한 번만 보낸다.
- 모델 선택처럼 proto에 없는 값이 필요하면 **gRPC metadata**로 받는다(Cloudflare AI Gateway의 `cf-aig-*` 헤더 역할). proto를 고치지 않아도 돼서 팀 합의 부담이 작다.

## 진행 상황

| # | 구성요소 | 구분 | 상태 |
|---|---|---|---|
| 0 | Mock provider | 개발 기반 | ✅ 기본 시나리오 (노브·장애 주입은 미구현) |
| 1 | 어댑터 + 스트리밍 + 취소 | **필수** | ✅ |
| 2 | 도구 실행 루프 | **필수** | ✅ |
| 3 | 라우팅 (난이도·도구 필요 여부) | 확장 | ⬜ |
| 4 | 재시도 + 백오프 | 확장 | ⬜ |
| 5 | 폴백 체인 | 확장 | ⬜ |
| 6 | 서킷 브레이커 | 확장 | ⬜ |
| 7 | 분석 (로그 + 집계, OpenTelemetry) | 확장 | ⬜ |
| 8 | 처리율 제한 | 확장 | ⬜ |
| 9 | 지출 한도 | 확장 | ⬜ |
| 10 | 응답 캐싱 | 확장 | ⬜ |

- 0→1→2가 과제 본체이고, 여기까지로 팀 기능은 완성이다.
- 확장 추천 순서: 4 재시도 → 7 분석 → 8 처리율 제한 → 5·6 폴백·서킷 브레이커 → 9·10.
  - 4: 무료·저가 모델에서 가장 자주 나는 실패(429)를 바로 줄인다.
  - 7을 8~10보다 먼저: 사용량·비용 측정 코드를 한도·캐시가 그대로 재사용한다.
  - 3 라우팅은 도구 루프가 돌아야 효과를 확인할 수 있다(고를 대상 중에 "도구를 쓸 수 있는 모델"이 있다).

## 코드 배치

```
main.go                 gRPC 서버 조립 (환경변수 읽기, 의존성 연결, graceful shutdown)   ✅
gateway.go              Generate RPC 구현                                                ✅
internal/llm/           OpenAI 호환 클라이언트: 요청 JSON, SSE 파싱                       ✅
internal/agent/         도구 루프 (Run)                                                   ✅
internal/tools/         get_current_time, search_memory                                  ✅
cmd/mockllm/            mock provider (compose 서비스 mock-llm, 같은 이미지의 /mockllm)   ✅
internal/router/        모델 선택 규칙                                                    ⬜
internal/resilience/    retry, breaker, fallback                                         ⬜
internal/limits/        ratelimit, budget, cache                                         ⬜
internal/stats/         요청 로그, 집계 (/stats 또는 OpenTelemetry)                       ⬜
```

환경변수: `LLM_BASE_URL`(비우면 mock), `LLM_API_KEY`, `LLM_MODEL`, `RAG_ADDR`(compose에서 `rag-service:50052`).

---

## 0. Mock provider

실제 provider와 **같은 wire format**을 내는 가짜 서버다. `LLM_BASE_URL`만 바꾸면 실제와 mock이 바뀌어야 한다.

**현재 상태** (`cmd/mockllm`)
- `POST /v1/chat/completions` → SSE. 마지막 메시지 기준 시나리오:
  - "몇 시" → `get_current_time` 호출, "학교" → `search_memory` 호출
  - `role:"tool"` → 그 결과를 읽어주는 텍스트
  - 그 외 → 고정 인사를 단어마다 100ms 간격으로
- 클라이언트가 끊으면(`r.Context().Done()`) 생성을 멈춘다.

**남은 것**
- `: OPENROUTER PROCESSING` 주석 줄, 끝에서 두 번째 `usage` 청크 (7 분석에 필요)
- 노브 (모델 프로필별 + 런타임 변경용 `POST /admin/faults`):
  - 시간: TTFT = 기본값 + 입력토큰 × k, 토큰 간격 ± 흔들림
  - 용량: 동시 슬롯 N, 초과 시 대기 또는 429/529
  - 실패: 시작 전 429(+`Retry-After`)·500·529, 스트림 도중 error 청크, 멈춤(stall)
- 끊겼을 때 실제로 보낸 토큰 수 로그

**함정**
- tool_call `arguments`는 여러 청크로 **쪼개서** 보내야 한다. 실제 OpenAI·OpenRouter가 그렇게 보내서, 안 쪼개면 어댑터 버그를 못 잡는다. 지금 mock은 한 번에 보낸다(쪼개진 경우는 `internal/llm` 단위 테스트가 대신 확인).

**완료 확인**: `curl -N`으로 토큰이 하나씩 보이는지, 중간에 Ctrl-C 하면 로그에 `client gone`이 찍히는지

## 1. 어댑터 + 스트리밍 + 취소

**현재 상태** (`internal/llm`)
- `Client.Stream(ctx, msgs, tools, onText)`: 텍스트 조각마다 `onText`를 부르고, 끝나면 `Result{Content, ToolCalls}`를 반환한다.
- ctx는 gRPC `stream.Context()`에서 온다. Spring이 끊으면 HTTP 연결까지 같이 끊긴다.
- `bufio.Scanner`로 줄 단위로 읽는다. `data:`가 아닌 줄은 무시하고, `data: [DONE]`이면 끝, `[DONE]` 없이 끊기면 에러.
- 2xx가 아니면 `llm: http <code>: <본문 앞 1KB>` 에러.

**남은 것**
- 상태 코드·`Retry-After`를 담은 에러 타입(`*HTTPError`): 4 재시도가 `errors.As`로 판별하는 데 필요
- `scanner.Buffer`로 한 줄 한도(기본 64KB) 늘리기
- `usage` 파싱 (요청에 `"stream_options":{"include_usage":true}` 추가)

**함정**
- **200인데 실패**: 스트림 도중 에러는 HTTP 200 안에 `{"error":…}` 청크로 온다. 정상 종료로 처리하면 안 된다. (처리함)
- **tool_call 조각**: OpenAI 형식은 `delta.tool_calls[i]`를 `index` 기준으로 이어 붙인다. 첫 조각에만 `id`와 `name`이 있다. (처리함)
- **Gemini 호환 API 차이** (처리함)
  - `index`가 없고 조각 하나가 완성된 호출 하나다. index가 없으면 새 칸에 추가한다.
  - `extra_content.google.thought_signature`를 다음 요청에 그대로 돌려줘야 한다. 없으면 400.
- **취소 과금**: OpenRouter 문서 기준, 취소가 실제로 생성을 멈추는 건 일부 upstream provider(OpenAI, Anthropic 등)뿐이다. Google, Groq 등은 끊어도 끝까지 과금될 수 있다.

**완료 확인**: grpcurl `-max-time 0.25` → gateway 로그에 `context deadline exceeded`, mock 로그에 `client gone`

## 2. 도구 실행 루프

**현재 상태** (`internal/agent`, `internal/tools`)
```
for round := 0; ; round++ {
  res := Stream(msgs, 도구 정의)          // 텍스트는 onText 로 바로 Spring 에 감
  ToolCalls 없음 → return nil            // gateway 가 done 전송
  round == 5 → 마지막 호출은 도구 없이 보내서 텍스트 답을 강제, 그래도 도구면 에러
  msgs += assistant{tool_calls}
  각 call 마다 msgs += tool{tool_call_id, content: 결과 JSON}
}
```
- `get_current_time`: `{"datetime": RFC3339(Asia/Seoul), "timezone": "Asia/Seoul"}`
  - distroless 이미지에는 시간대 DB가 없어서 `import _ "time/tzdata"`를 넣었다.
- `search_memory`: 인자에서 `query`만 받는다. `conversation_id`는 **요청에서** 가져온다(모델이 정한 값 사용 금지). `Retrieve`를 3초 제한으로 호출 → `{"chunks":[…]}`.
- 도구 오류는 Go error로 끝내지 않고 모델에게 `{"error":"…"}`로 돌려준다: 알 수 없는 도구, 깨진 인자 JSON, 빈 query, timeout, RAG 실패.
- RAG 실패(`{"error":"rag unavailable: …"}`)와 결과 없음(`{"chunks":[]}`)을 구분한다.
- 모델이 도구 호출 전에 쓰는 텍스트("확인해볼게요")는 그대로 사용자에게 보낸다. 도구 인자·결과만 숨긴다.

**남은 것**
- 한 응답에 도구가 여러 개면 병렬 실행 (지금은 순서대로). `sync.WaitGroup` + `results[i]`.

**완료 확인**: README 예시 질문 "지금 한국 시간으로 몇 시야?", "내가 어느 학교 다닌다고 했지?" (Gemini + RAG mock으로 확인함)

## 3. 라우팅 (어떤 모델로 보낼지)

**최소 구현**
- 요청 하나당 `Route{Model, Tier(small|large), NeedsTools}`를 한 번 결정한다.
- v1은 **규칙 기반**(0ms):
  - 시간·날짜 단어("몇 시", "오늘", "지금") → NeedsTools
  - 기억 단어("기억", "~했지", "전에 말한") → NeedsTools
  - 긴 입력, 코드 블록, "왜·비교·설명·분석" → large, 나머지는 small
- 모델표에서 조건에 맞는 첫 모델을 고른다. 모델표 항목: `{id, tier, supportsTools, 입력·출력 단가, ctxLen, fallback}`
  - NeedsTools면 `supportsTools`인 모델만 고려한다(OpenRouter 모델 목록의 `supported_parameters`에 `tools`가 있어야 함).
- 클라이언트가 직접 모델을 골랐다면(metadata) 그 선택이 규칙보다 우선이다.

**함정**
- "도구가 필요한지"를 라우터가 맞힐 필요는 없다. 도구는 항상 넘기고 `tool_choice:auto`로 두면 실제로 쓸지는 모델이 정한다. 라우터 판정은 **도구 사용이 안정적인 모델로 보내기 위한 것**이다.
- v2로 싼 모델에게 분류를 맡기면(JSON 출력) 정확도는 오르지만 TTFT가 수백 ms 늘어난다. timeout을 걸고, 실패하면 v1 결과를 쓴다.

**완료 확인**: 라우팅 결과(모델, 이유)가 요청 로그에 남는지

## 4. 재시도 + 백오프

**최소 구현** (`llm.Stream` 바깥을 감싼다)
- 재시도 대상: 408, 429, 5xx(529 포함), 네트워크 오류, **첫 토큰 전** timeout
- 재시도 안 함: 400, 401, 402(크레딧 부족), 404, `context.Canceled`(사용자가 끔)
- 대기 시간 = `rand(0, min(cap, base·2^attempt))` (full jitter). `Retry-After`가 있으면 그 값을 우선한다.
- 최대 3회. 대기 중에도 `ctx.Done()`이면 즉시 중단 (`select` + `time.After`)

**함정**
- 첫 토큰을 보낸 뒤에는 재시도하면 안 된다. 같은 답이 화면에 두 번 붙는다. `onText`를 감싸서 "한 번이라도 보냈나"를 추적한다.

**완료 확인**: mock 429 주입 → 로그에 attempt 2·3이 찍히고 결국 성공

## 5. 폴백 체인

**최소 구현**
- 모델 A 실패(재시도까지 소진) → 모델표의 fallback B → C
- **첫 토큰 전 실패만** 넘긴다. 스트림 중간 실패는 error로 종료한다.
- 몇 번째 단계가 응답했는지(`step`, Cloudflare의 `cf-aig-step`)를 로그와 trailer metadata에 남긴다.

**함정**
- 보낸 뒤 실패를 폴백하면 답이 섞인다 (4와 같은 "보냈나" 추적 필요).
- 도구 루프와 합칠 때는 **라운드마다** 폴백이 가능하다. 2라운드에서 모델이 바뀌어도 메시지 형식이 같아서(OpenAI 호환) 이어서 진행된다. 단 Gemini의 `thought_signature`는 Gemini가 아닌 모델에게 보내면 무시되거나 거부될 수 있으니 확인한다.

**완료 확인**: A에 500 100% 주입 → 응답은 B가 하고 `step=2`

## 6. 서킷 브레이커

**최소 구현** (모델·provider별 하나씩)
- Closed: 연속 실패 N(=5)회 → Open
- Open: cooldown(=30s) 동안 바로 거절 → 폴백이 다음 모델로 즉시 넘어감
- HalfOpen: 시험 요청 1개만 통과 → 성공하면 Closed, 실패하면 다시 Open
- 실패로 세는 것: 재시도 대상 오류만. 400·취소는 세지 않는다(사용자 잘못이나 취소로 회로가 열리면 안 됨).

**완료 확인**: 장애 주입 → 처음 N번 뒤로는 A를 시도하지 않고 바로 B로 감(지연이 줄어드는 게 보임) → 주입 해제 → cooldown 뒤 A로 복귀

## 7. 분석 (요청·토큰·비용·에러·성능, 캐시 적중률, 비용 추정)

**최소 구현**
- 요청 하나 = 구조화 로그 한 줄(`log/slog` JSON):
  `request_id, conversation_id, model, route_reason, step, rounds, tools[], status, ttft_ms, latency_ms, in_tokens, out_tokens, cost_usd, cost_estimated, cache_hit, error`
- 메모리 집계를 `GET /stats`(JSON)로 노출: 총 요청, 모델별 수, 에러율, 캐시 적중률, 평균 TTFT, p95 지연, 총 비용
  - gRPC 포트 옆에 작은 HTTP 포트(예: :9090)를 하나 연다.
- 비용:
  - usage가 오면 `in × 입력단가 + out × 출력단가`(모델표 단가)
  - 취소 등으로 usage가 안 오면 보낸 글자 수로 **추정**하고 `cost_estimated=true`로 표시
  - OpenRouter usage에 비용 필드가 오면 그걸 우선한다.

**OpenTelemetry로 하는 경우** (`/stats` 대신 또는 함께)
- trace: `Generate` 하나가 span 하나. 그 아래 `llm.Stream` 1·2회차, `tools.Call`, `RAG Retrieve`가 자식 span. 시퀀스 다이어그램이 Jaeger에 그대로 보인다.
- gRPC는 `otelgrpc` 핸들러를 서버와 RAG 클라이언트에 한 줄씩 붙이면 자동 계측되고, Spring도 OTel을 쓰면 하나의 trace로 이어진다.
- metrics: 요청 수, 에러 수, TTFT, 도구 호출 수, 토큰 수
- compose에 Jaeger 컨테이너를 붙여 `localhost:16686`에서 확인한다.
- go.mod가 `go 1.23.0`(Docker 이미지 Go 1.23)이라 이 버전을 지원하는 OTel 버전을 골라야 한다.

**함정**
- 도구 루프는 모델 호출이 여러 번이라 토큰·비용을 **라운드별로 합산**해야 한다.
- TTFT는 "사용자가 첫 글자를 본 시점" 기준으로 잰다. 도구 라운드가 있으면 그만큼 길어지는 게 정상이다.

**완료 확인**: 부하를 조금 준 뒤 `/stats`(또는 Jaeger) 숫자와 로그 줄 수가 맞는지

## 8. 처리율 제한

**최소 구현** (`Generate` 맨 앞)
- 대화(또는 metadata의 user) 단위 **token bucket**: 분당 요청 N개. 초과하면 `codes.ResourceExhausted`로 거절
  - 직접 구현하거나 `golang.org/x/time/rate` 사용 (go.mod의 go 버전 확인)
- 확장: 토큰 단위 버킷. 요청 전에 예상 토큰(입력 추정 + max_tokens)을 차감하고, 끝나면 실제 사용량으로 정산
- provider 쪽 한도(우리 API 키의 분당 한도)는 전역 버킷 하나로 처리한다.

**함정**
- 인스턴스가 여러 개면 메모리 버킷은 인스턴스별로 따로 센다. 이 프로젝트는 1개라 괜찮다고 명시하고 넘어간다.

## 9. 지출 한도

**최소 구현**: 예약 → 정산 (카드 선승인과 같은 방식)
- 요청 전: 최대 비용(입력 + max_tokens 기준)을 **예약**. `spent + reserved + max > limit`이면 거절
- 스트리밍 중: 잠금이나 DB 없음
- 끝(성공·실패·취소 모두): `defer`로 예약 해제 + 실제 비용 기록
- 단위: 일별·모델별 한도(Cloudflare 방식). 한도를 넘으면 차단하는 대신 **싼 모델로 강등**하는 옵션도 가능(시연할 때 잘 보임).

**함정**
- 예약이 없으면 스트림 10개를 동시에 열어 한도를 넘길 수 있다.
- 정산은 반드시 `defer`로. 패닉이나 취소 경로에서 예약이 새면 한도가 영영 안 풀린다.

## 10. 응답 캐싱

**최소 구현**
- 키 = sha256(모델 + 정규화한 messages + temperature 등 파라미터)
- 저장소: 메모리 TTL 캐시 (최대 항목 수 + 오래된 것부터 제거)
- 적중하면 저장된 텍스트를 **청크로 나눠 스트림으로 재생**한다. Spring 쪽은 같은 경로를 탄다.
- metadata로 우회 가능하게: `cache: skip`

**함정**
- **도구를 쓴 응답은 캐시하지 않는다.** 현재 시각이나 메모리 검색 결과는 매번 달라진다.
- temperature > 0이면 캐시하는 게 의미상 맞는지 정해야 한다(기본은 0일 때만).
- 대화 맥락이 매번 달라서 실제 적중률은 낮다. 그래서 캐시 적중률이 **이걸 정직하게 보여주는 지표**가 된다.
