package com.example.chat;

import com.example.chat.proto.ChatChunk;
import com.example.chat.proto.ChatRequest;
import com.example.chat.proto.LlmGatewayGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
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
    "app.chat.timeout-ms=5000",
    "app.chat.heartbeat-ms=100",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
class ChatControllerTest {
    private record GatewayCall(StreamObserver<ChatChunk> stream, CompletableFuture<Void> cancelled) {}
    private record Exchange(GatewayCall call, CompletableFuture<HttpResponse<InputStream>> response) {}
    private static final LinkedBlockingQueue<GatewayCall> streams = new LinkedBlockingQueue<>();
    private static Server gateway;
    private HttpClient http;

    @BeforeEach
    void createHttpClient() {
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void closeHttpClient() {
        http.shutdownNow();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) throws Exception {
        gateway = ServerBuilder.forPort(0).addService(new LlmGatewayGrpc.LlmGatewayImplBase() {
            @Override
            public void generate(ChatRequest request, StreamObserver<ChatChunk> observer) {
                CompletableFuture<Void> cancelled = new CompletableFuture<>();
                ((ServerCallStreamObserver<ChatChunk>) observer).setOnCancelHandler(() -> cancelled.complete(null));
                streams.add(new GatewayCall(observer, cancelled));
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
        Exchange exchange = connect();
        StreamObserver<ChatChunk> stream = exchange.call().stream();
        stream.onNext(ChatChunk.newBuilder().setDelta("안녕").build());
        HttpResponse<InputStream> response = exchange.response().get(10, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("text/event-stream"));
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
        try (BufferedReader reader = reader(response)) {
            // Read the first event before allowing the gateway to send the remaining answer.
            assertEquals("event:delta\ndata:{\"content\":\"안녕\"}\n", readEvent(reader));
            stream.onNext(ChatChunk.newBuilder().setDelta("하세요").setDone(true).build());
            assertEquals("event:delta\ndata:{\"content\":\"하세요\"}\n", readEvent(reader));
            stream.onCompleted();
            assertEquals("event:done\ndata:{\"conversationId\":\"sse-m2\"}\n", readEvent(reader));
            assertEquals("", readEvent(reader));
        }
    }

    @Test
    void reportsGatewayFailureBeforeFirstDelta() throws Exception {
        Exchange exchange = connect();
        exchange.call().stream().onError(Status.UNAVAILABLE.withDescription("private provider details").asRuntimeException());
        try (BufferedReader reader = reader(exchange.response().get(10, TimeUnit.SECONDS))) {
            assertEquals("event:error\ndata:{\"message\":\"답변 생성 중 오류가 발생했어요. 다시 시도해 주세요.\"}\n", readEvent(reader));
            assertEquals("", readEvent(reader));
        }
    }

    @Test
    void reportsFailureAfterDoneFlagWithoutSendingDone() throws Exception {
        Exchange exchange = connect();
        exchange.call().stream().onNext(ChatChunk.newBuilder().setDelta("일부 답변").setDone(true).build());
        try (BufferedReader reader = reader(exchange.response().get(10, TimeUnit.SECONDS))) {
            assertTrue(readEvent(reader).startsWith("event:delta\n"));
            exchange.call().stream().onError(Status.INTERNAL.asRuntimeException());
            assertTrue(readEvent(reader).startsWith("event:error\n"));
            assertEquals("", readEvent(reader));
        }
    }

    @Test
    void rejectsCompletionWithoutDoneFlag() throws Exception {
        Exchange exchange = connect();
        exchange.call().stream().onCompleted();
        try (BufferedReader reader = reader(exchange.response().get(10, TimeUnit.SECONDS))) {
            assertEquals("event:error\ndata:{\"message\":\"응답이 중간에 끊겼어요. 다시 시도해 주세요.\"}\n", readEvent(reader));
            assertEquals("", readEvent(reader));
        }
    }

    @Test
    void sendsHeartbeatThenTimeoutAndCancelsGateway() throws Exception {
        Exchange exchange = connect();
        try (BufferedReader reader = reader(exchange.response().get(10, TimeUnit.SECONDS))) {
            assertEquals(":keepalive\n", readFrame(reader));
            assertEquals("event:error\ndata:{\"message\":\"응답 시간이 초과됐어요. 다시 시도해 주세요.\"}\n", readEvent(reader));
            assertEquals("", readEvent(reader));
            exchange.call().cancelled().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelsGatewayWhenBrowserDisconnectsBeforeDeadline() throws Exception {
        Exchange exchange = connect();
        try (BufferedReader reader = reader(exchange.response().get(10, TimeUnit.SECONDS))) {
            assertEquals(":keepalive\n", readFrame(reader));
        }
        http.shutdownNow();
        // A two-second bound distinguishes disconnect cancellation from the five-second deadline.
        exchange.call().cancelled().get(2, TimeUnit.SECONDS);
    }

    private Exchange connect() throws Exception {
        String baseUrl = System.getenv().getOrDefault("SSE_TEST_URL", "http://localhost:" + port);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"conversationId":"sse-m2","messages":[{"role":"user","content":"안녕"}]}
                """))
            .build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        GatewayCall call = streams.poll(10, TimeUnit.SECONDS);
        assertNotNull(call, "The HTTP request must reach the gRPC gateway");
        return new Exchange(call, response);
    }

    private BufferedReader reader(HttpResponse<InputStream> response) {
        assertEquals(200, response.statusCode());
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private String readEvent(BufferedReader reader) throws Exception {
        String frame;
        do {
            frame = readFrame(reader);
        } while (frame.startsWith(":"));
        return frame;
    }

    private String readFrame(BufferedReader reader) throws Exception {
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
