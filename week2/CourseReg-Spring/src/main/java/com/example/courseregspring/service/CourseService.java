package com.example.courseregspring.service;

import com.example.courseregspring.domain.Course;
import com.example.courseregspring.repository.CourseRepository;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class CourseService {
    private final CourseRepository courseRepository;
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public boolean enroll(long courseId) {
        Object lock = locks.computeIfAbsent(courseId, id -> new Object());
        synchronized (lock) {
            Course course = courseRepository.findById(courseId).orElse(null);
            if (course.getLeftSeats() == 0) {
                return false;
            }
            course.decreaseLeftSeats();
            courseRepository.save(course);
            return true;
        }
    }
}
