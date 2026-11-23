package com.example.chat;

import com.example.chat.proto.ChatChunk;
import com.example.chat.proto.ChatRequest;
import com.example.chat.proto.LlmGatewayGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.port=${SSE_TEST_PORT:0}",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
class ChatControllerTest {
    private static final LinkedBlockingQueue<StreamObserver<ChatChunk>> streams = new LinkedBlockingQueue<>();
    private static Server gateway;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) throws Exception {
        gateway = ServerBuilder.forPort(0).addService(new LlmGatewayGrpc.LlmGatewayImplBase() {
            @Override
            public void generate(ChatRequest request, StreamObserver<ChatChunk> observer) {
                streams.add(observer);
            }
        }).build().start();
        registry.add("app.gateway.target", () -> "localhost:" + gateway.getPort());
    }

    @AfterAll
    static void stopGateway() throws Exception {
        gateway.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void streamsEachDeltaBeforeGatewayCompletes() throws Exception {
        String baseUrl = System.getenv().getOrDefault("SSE_TEST_URL", "http://localhost:" + port);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"conversationId":"sse-m2","messages":[{"role":"user","content":"안녕"}]}
                """))
            .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            StreamObserver<ChatChunk> stream = streams.poll(10, TimeUnit.SECONDS);
            assertNotNull(stream, "The HTTP request must reach the gRPC gateway");
            stream.onNext(ChatChunk.newBuilder().setDelta("안녕").build());
            HttpResponse<java.io.InputStream> response = pending.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("text/event-stream"));
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                // Read the first event before allowing the gateway to send the remaining answer.
                assertEquals("event:delta\ndata:{\"content\":\"안녕\"}\n", readEvent(reader));
                stream.onNext(ChatChunk.newBuilder().setDelta("하세요").setDone(true).build());
                assertEquals("event:delta\ndata:{\"content\":\"하세요\"}\n", readEvent(reader));
                stream.onCompleted();
                assertEquals("event:done\ndata:{\"conversationId\":\"sse-m2\"}\n", readEvent(reader));
                assertEquals("", readEvent(reader));
            }
        }
    }

    private String readEvent(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder event = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    event.append(line).append('\n');
                }
                return event.toString();
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }).get(10, TimeUnit.SECONDS);
    }
}
