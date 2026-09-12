package com.example.courseregspring;

import org.springframework.boot.SpringApplication;

public class TestCourseRegSpringApplication {

    public static void main(String[] args) {
        SpringApplication.from(CourseRegSpringApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
