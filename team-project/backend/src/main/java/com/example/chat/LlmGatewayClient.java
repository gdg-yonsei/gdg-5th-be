package com.example.chat;

import com.example.chat.proto.ChatChunk;
import com.example.chat.proto.ChatMessage;
import com.example.chat.proto.ChatRequest;
import com.example.chat.proto.LlmGatewayGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LlmGatewayClient {
    private final ManagedChannel channel;
    private final LlmGatewayGrpc.LlmGatewayStub stub;

    public LlmGatewayClient(@Value("${app.gateway.target}") String target) {
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = LlmGatewayGrpc.newStub(channel);
    }

    public void generate(ChatController.Request request, StreamObserver<ChatChunk> observer) {
        ChatRequest.Builder body = ChatRequest.newBuilder().setConversationId(request.conversationId());
        for (ChatController.Message message : request.messages()) {
            body.addMessages(ChatMessage.newBuilder().setRole(message.role()).setContent(message.content()));
        }
        stub.generate(body.build(), observer);
    }

    @PreDestroy
    public void close() {
        channel.shutdownNow();
    }
}
