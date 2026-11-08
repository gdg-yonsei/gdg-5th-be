package com.example.chat;

import java.time.Instant;
import java.util.UUID;

public record MemoryRequestedEvent(
    int schemaVersion,
    String type,
    String eventId,
    String occurredAt,
    String conversationId,
    String userMessage,
    String assistantMessage
) {
    public static MemoryRequestedEvent create(String conversationId, String userMessage, String assistantMessage) {
        return new MemoryRequestedEvent(1, "memory.add.requested", UUID.randomUUID().toString(),
            Instant.now().toString(), conversationId, userMessage, assistantMessage);
    }
}
