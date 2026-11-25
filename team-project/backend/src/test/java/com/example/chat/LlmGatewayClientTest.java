package com.example.chat;

import com.example.chat.proto.ChatChunk;
import com.example.chat.proto.ChatRequest;
import com.example.chat.proto.LlmGatewayGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmGatewayClientTest {
    private final ChatController.Request request = new ChatController.Request("sse-m1", List.of(
        new ChatController.Message("user", "안녕"),
        new ChatController.Message("assistant", "안녕하세요"),
        new ChatController.Message("user", "다시 인사해줘")));

    @Test
    void mapsRequestAndReceivesAsyncStream() throws Exception {
        AtomicReference<ChatRequest> received = new AtomicReference<>();
        Server server = ServerBuilder.forPort(0).addService(new LlmGatewayGrpc.LlmGatewayImplBase() {
            @Override
            public void generate(ChatRequest body, StreamObserver<ChatChunk> observer) {
                received.set(body);
                observer.onNext(ChatChunk.newBuilder().setDelta("안녕").build());
                observer.onNext(ChatChunk.newBuilder().setDelta("하세요").build());
                observer.onNext(ChatChunk.newBuilder().setDone(true).build());
                observer.onCompleted();
            }
        }).build().start();
        LlmGatewayClient client = new LlmGatewayClient("localhost:" + server.getPort());
        try {
            List<ChatChunk> chunks = generate(client);
            assertEquals("sse-m1", received.get().getConversationId());
            assertEquals(request.messages().size(), received.get().getMessagesCount());
            for (int i = 0; i < request.messages().size(); i++) {
                assertEquals(request.messages().get(i).role(), received.get().getMessages(i).getRole());
                assertEquals(request.messages().get(i).content(), received.get().getMessages(i).getContent());
            }
            assertEquals(List.of("안녕", "하세요", ""), chunks.stream().map(ChatChunk::getDelta).toList());
            assertTrue(chunks.get(2).getDone());
        } finally {
            client.close();
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GATEWAY_TEST_TARGET", matches = ".+")
    void receivesGoMockResponse() throws Exception {
        LlmGatewayClient client = new LlmGatewayClient(System.getenv("GATEWAY_TEST_TARGET"));
        try {
            List<ChatChunk> chunks = generate(client);
            assertEquals(2, chunks.size());
            assertEquals("안녕하세요", chunks.get(0).getDelta());
            assertTrue(chunks.get(1).getDone());
        } finally {
            client.close();
        }
    }

    private List<ChatChunk> generate(LlmGatewayClient client) throws Exception {
        List<ChatChunk> chunks = new ArrayList<>();
        CompletableFuture<List<ChatChunk>> result = new CompletableFuture<>();
        client.generate(request, new StreamObserver<>() {
            @Override public void onNext(ChatChunk chunk) { chunks.add(chunk); }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onCompleted() { result.complete(chunks); }
        }, 10000);
        return result.get(10, TimeUnit.SECONDS);
    }
}
