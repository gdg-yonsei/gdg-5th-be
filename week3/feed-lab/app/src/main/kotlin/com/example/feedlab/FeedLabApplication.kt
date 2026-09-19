package com.example.feedlab

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class FeedLabApplication

fun main(args: Array<String>) {
    runApplication<FeedLabApplication>(*args)
}

@ConfigurationProperties("feedlab")
data class LabProps(
    val hybridThreshold: Int = 10_000,   // v4: 팔로워가 이 수를 넘으면 팬아웃하지 않는다
    val fanoutBatchSize: Int = 1,        // feeds INSERT 를 몇 행씩 묶어 보낼지. 1 = 팔로워 한 명당 한 번 왕복
    val workerIntervalMs: Long = 1_000,  // v3 워커가 깨어나는 주기
    val loadgenPort: Int = 8081,
)

/** 시연 중에 화면에서 바꿀 수 있는 값. 시작값은 환경변수(LabProps)에서 온다. */
@org.springframework.stereotype.Component
class LiveSettings(props: LabProps) {
    @Volatile var fanoutBatchSize: Int = props.fanoutBatchSize
    @Volatile var hybridThreshold: Int = props.hybridThreshold
}

/**
 * 커넥션 풀을 둘로 나눈다.
 *  - main: 피드 조회 / 글 작성. 부하가 몰리면 여기서 줄을 선다 (시연에서 보여줄 병목).
 *  - ops : 워커, 메트릭, 화면 보조 API. main 이 꽉 차도 대시보드는 계속 움직여야 한다.
 */
@Configuration
class DataSourceConfig {
    @Bean @Primary
    @ConfigurationProperties("feedlab.datasource.main")
    fun mainDataSource(): HikariDataSource = HikariDataSource()

    @Bean
    @ConfigurationProperties("feedlab.datasource.ops")
    fun opsDataSource(): HikariDataSource = HikariDataSource()

    @Bean @Primary
    fun jdbcTemplate(mainDataSource: DataSource): JdbcTemplate = JdbcTemplate(mainDataSource)

    @Bean
    fun opsJdbc(@Qualifier("opsDataSource") ds: DataSource): JdbcTemplate = JdbcTemplate(ds)
}
