package com.example.chat;

import com.example.chat.proto.ChatChunk;
import com.example.chat.proto.ChatRequest;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final LlmGatewayClient gateway;
    private final long timeoutMillis;
    private final long heartbeatMillis;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();

    public ChatController(LlmGatewayClient gateway,
                          @Value("${app.chat.timeout-ms}") long timeoutMillis,
                          @Value("${app.chat.heartbeat-ms}") long heartbeatMillis) {
        if (timeoutMillis <= 0 || heartbeatMillis <= 0) {
            throw new IllegalArgumentException("Chat timeout and heartbeat must be positive");
        }
        this.gateway = gateway;
        this.timeoutMillis = timeoutMillis;
        this.heartbeatMillis = heartbeatMillis;
    }

    @PreDestroy
    public void close() {
        heartbeats.shutdownNow();
    }

    // TODO (팀원 5): 대화 생성, 목록 조회, 메시지 조회 및 삭제는 대화 관리 제어기에 구현하세요.
    public record Message(@NotNull @Pattern(regexp = "user|assistant") String role,
                          @NotBlank @Size(max = 16000) String content) {}
    public record Request(@NotBlank @Size(max = 100) String conversationId,
                          @NotEmpty @Size(max = 100) List<@NotNull @Valid Message> messages) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@Valid @RequestBody Request body) {
        // TODO (팀원 5): 전달받은 대화 식별자가 존재하는지 확인하고 이번 사용자 메시지를 저장하세요.
        // The gRPC deadline reports a timeout before the servlet's fallback timeout.
        SseEmitter emitter = new SseEmitter(timeoutMillis + 1000);
        class ChatStream implements ClientResponseObserver<ChatRequest, ChatChunk> {
            private final StringBuilder answer = new StringBuilder();
            private boolean done;
            private boolean terminated;
            private ClientCallStreamObserver<ChatRequest> call;
            private ScheduledFuture<?> heartbeat;

            @Override
            public synchronized void beforeStart(ClientCallStreamObserver<ChatRequest> stream) {
                call = stream;
                if (terminated) {
                    call.cancel("SSE connection closed", null);
                    return;
                }
                heartbeat = heartbeats.scheduleAtFixedRate(this::heartbeat,
                    heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
            }

            private void stop() {
                if (heartbeat != null) heartbeat.cancel(false);
                if (call != null) call.cancel("SSE stream ended", null);
            }

            private synchronized void disconnect() {
                if (terminated) return;
                terminated = true;
                stop();
            }

            private synchronized void fail(String message) {
                if (terminated) return;
                terminated = true;
                stop();
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", message), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException | IllegalStateException error) {
                    emitter.completeWithError(error);
                }
            }

            private synchronized void heartbeat() {
                if (terminated) return;
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException error) {
                    disconnect();
                    emitter.completeWithError(error);
                }
            }

            @Override
            public synchronized void onNext(ChatChunk chunk) {
                if (terminated) return;
                try {
                    if (!chunk.getDelta().isEmpty()) {
                        answer.append(chunk.getDelta());
                        emitter.send(SseEmitter.event().name("delta")
                            .data(Map.of("content", chunk.getDelta()), MediaType.APPLICATION_JSON));
                    }
                    done |= chunk.getDone();
                } catch (IOException | IllegalStateException error) {
                    disconnect();
                    emitter.completeWithError(error);
                }
            }

            @Override
            public void onError(Throwable error) {
                fail(Status.fromThrowable(error).getCode() == Status.Code.DEADLINE_EXCEEDED
                    ? "응답 시간이 초과됐어요. 다시 시도해 주세요."
                    : "답변 생성 중 오류가 발생했어요. 다시 시도해 주세요.");
            }

            @Override
            public synchronized void onCompleted() {
                if (terminated) return;
                if (!done) {
                    fail("응답이 중간에 끊겼어요. 다시 시도해 주세요.");
                    return;
                }
                terminated = true;
                stop();
                try {
                    // TODO (팀원 5): answer.toString()을 저장하고 대화 갱신 시각을 반영하세요.
                    // TODO (팀원 4): 정상 완료된 답변으로 메모리 추가 이벤트를 비동기 발행하세요.
                    emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("conversationId", body.conversationId()), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException | IllegalStateException error) {
                    emitter.completeWithError(error);
                }
            }
        }
        ChatStream stream = new ChatStream();
        emitter.onCompletion(stream::disconnect);
        emitter.onError(error -> stream.disconnect());
        emitter.onTimeout(() -> stream.fail("응답 시간이 초과됐어요. 다시 시도해 주세요."));
        try {
            gateway.generate(body, stream, timeoutMillis);
        } catch (RuntimeException error) {
            stream.onError(error);
        }
        return ResponseEntity.ok().header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no").body(emitter);
    }
}
