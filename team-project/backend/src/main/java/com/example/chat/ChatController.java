package com.example.chat;

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
    // TODO (팀원 5): 대화 생성, 목록 조회, 메시지 조회 및 삭제는 대화 관리 제어기에 구현하세요.
    public record Message(@NotNull @Pattern(regexp = "user|assistant") String role,
                          @NotBlank @Size(max = 16000) String content) {}
    public record Request(@NotBlank @Size(max = 100) String conversationId,
                          @NotEmpty @Size(max = 100) List<@NotNull @Valid Message> messages) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@Valid @RequestBody Request body) throws IOException {
        // TODO (팀원 5): 전달받은 대화 식별자가 존재하는지 확인하고 이번 사용자 메시지를 저장하세요.
        // TODO (팀원 1): 대규모 언어 모델 게이트웨이를 호출하고 응답 스트림을 서버 전송 이벤트로 전달하세요.
        // TODO (팀원 4): 응답 완료 후 래빗엠큐로 메모리 추가 이벤트를 비동기로 발행하세요.
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().name("delta")
            .data(Map.of("content", "안녕하세요"), MediaType.APPLICATION_JSON));
        emitter.send(SseEmitter.event().name("done")
            .data(Map.of("conversationId", body.conversationId()), MediaType.APPLICATION_JSON));
        // TODO (팀원 5): 완성된 답변을 저장하고 대화의 최근 갱신 시각을 반영하세요.
        // TODO (팀원 5): 실패하거나 중단된 응답의 저장 정책과 중복 저장 방지를 구현하세요.
        emitter.complete();
        return ResponseEntity.ok().header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no").body(emitter);
    }
}
