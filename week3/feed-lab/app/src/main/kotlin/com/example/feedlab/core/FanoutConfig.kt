package com.example.feedlab.core

import com.example.feedlab.LiveSettings
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
class FanoutConfig {
    /** v2 가 요청 스레드에서 쓰는 팬아웃 (main 풀, 요청 트랜잭션에 참여) */
    @Bean @Primary
    fun fanout(jdbc: JdbcTemplate, live: LiveSettings) = Fanout(jdbc) { live.fanoutBatchSize }

    /** 워커가 쓰는 팬아웃 (ops 풀) */
    @Bean
    fun workerFanout(@Qualifier("opsJdbc") jdbc: JdbcTemplate, live: LiveSettings) = Fanout(jdbc) { live.fanoutBatchSize }
}
