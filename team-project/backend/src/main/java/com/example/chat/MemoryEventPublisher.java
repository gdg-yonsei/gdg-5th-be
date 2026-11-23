package com.example.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class MemoryEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(MemoryEventPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String routingKey;

    public MemoryEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
                                @Value("${app.memory.exchange}") String exchange,
                                @Value("${app.memory.routing-key}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    @Async
    public void publish(MemoryRequestedEvent event) {
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("UTF-8");
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(event.eventId());
            rabbitTemplate.send(exchange, routingKey,
                new Message(objectMapper.writeValueAsBytes(event), properties));
        } catch (Exception e) {
            // 발행 실패는 이미 완료된 채팅 응답에 영향을 주지 않습니다.
            log.error("Failed to publish memory event {}", event.eventId(), e);
        }
    }
}
