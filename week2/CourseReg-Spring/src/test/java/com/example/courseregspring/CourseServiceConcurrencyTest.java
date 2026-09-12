package com.example.courseregspring;

import com.example.courseregspring.domain.Course;
import com.example.courseregspring.repository.CourseRepository;
import com.example.courseregspring.service.CourseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CourseServiceConcurrencyTest {

    private static final int CAPACITY = 100;
    private static final int REQUEST_COUNT = 500;
    private static final int THREAD_POOL_SIZE = 8;

    @Autowired
    private CourseService courseService;

    @Autowired
    private CourseRepository courseRepository;

    private Long courseId;

    @BeforeEach
    void setUp() {
        courseId = courseRepository.save(new Course("동시성 테스트 강의", CAPACITY)).getId();
    }

    @Test
    void 정원_100명_강의에_500명이_동시에_신청해도_100명만_성공해야_한다() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(REQUEST_COUNT);

        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    if (courseService.enroll(courseId)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        Course result = courseRepository.findById(courseId).orElseThrow();
        System.out.println("성공한 신청 수: " + successCount.get());
        System.out.println("남은 좌석 수: " + result.getLeftSeats());

        assertThat(successCount.get()).isEqualTo(CAPACITY);
        assertThat(result.getLeftSeats()).isEqualTo(0);
    }
}
