package com.example.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    public record CreateRequest(@NotBlank @Size(max = 80) String title) {}
    public record Conversation(String id, String title, String createdAt, String updatedAt) {}
    public record Message(String id, String role, String content, String status, String createdAt) {}

    @GetMapping
    public List<Conversation> list() {
        // TODO (팀원 5): 데이터베이스에서 최근 갱신 순으로 대화 목록을 조회하세요.
        return List.of();
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@Valid @RequestBody CreateRequest request) {
        // TODO (팀원 5): 생성한 대화를 데이터베이스에 저장하세요.
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        var conversation = new Conversation(id, request.title(), now, now);
        return ResponseEntity.created(URI.create("/api/conversations/" + id)).body(conversation);
    }

    @GetMapping("/{conversationId}/messages")
    public List<Message> messages(@PathVariable String conversationId) {
        // TODO (팀원 5): 대화 존재 여부를 확인하고 메시지를 시간순으로 조회하세요.
        return List.of();
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(@PathVariable String conversationId) {
        // TODO (팀원 5): 대화와 메시지를 데이터베이스에서 삭제하세요.
        return ResponseEntity.noContent().build();
    }
}
