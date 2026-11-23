package com.example.chat;

import com.example.chat.proto.ChatChunk;
import io.grpc.stub.StreamObserver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final LlmGatewayClient gateway;

    public ChatController(LlmGatewayClient gateway) {
        this.gateway = gateway;
    }

    // TODO (팀원 5): 대화 생성, 목록 조회, 메시지 조회 및 삭제는 대화 관리 제어기에 구현하세요.
    public record Message(@NotNull @Pattern(regexp = "user|assistant") String role,
                          @NotBlank @Size(max = 16000) String content) {}
    public record Request(@NotBlank @Size(max = 100) String conversationId,
                          @NotEmpty @Size(max = 100) List<@NotNull @Valid Message> messages) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@Valid @RequestBody Request body) {
        // TODO (팀원 5): 전달받은 대화 식별자가 존재하는지 확인하고 이번 사용자 메시지를 저장하세요.
        SseEmitter emitter = new SseEmitter();
        gateway.generate(body, new StreamObserver<>() {
            private final StringBuilder answer = new StringBuilder();
            private boolean done;

            @Override
            public void onNext(ChatChunk chunk) {
                try {
                    if (!chunk.getDelta().isEmpty()) {
                        answer.append(chunk.getDelta());
                        emitter.send(SseEmitter.event().name("delta")
                            .data(Map.of("content", chunk.getDelta()), MediaType.APPLICATION_JSON));
                    }
                    done |= chunk.getDone();
                } catch (IOException | IllegalStateException error) {
                    emitter.completeWithError(error);
                }
            }

            @Override
            public void onError(Throwable error) {
                emitter.completeWithError(error);
            }

            @Override
            public void onCompleted() {
                if (!done) {
                    emitter.completeWithError(new IllegalStateException("Gateway completed without done"));
                    return;
                }
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
        });
        return ResponseEntity.ok().header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no").body(emitter);
    }
}
