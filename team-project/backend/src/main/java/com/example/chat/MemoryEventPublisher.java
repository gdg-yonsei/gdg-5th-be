package com.example.chat;

import org.springframework.stereotype.Service;

@Service
public class MemoryEventPublisher {
    public void publish(MemoryRequestedEvent event) {
        // TODO (팀원 4): 래빗엠큐 메시지 전송 도구로 메모리 추가 이벤트를 발행하세요.
        // 교환기와 라우팅 키는 애플리케이션 설정을, 메시지 본문은 이벤트 스키마를 참고하세요.
        // 응답 완료 후 비동기로 호출하고 발행 실패 처리도 구현하세요.
    }
}
