package com.example.courseregspring.service;

import com.example.courseregspring.domain.Course;
import com.example.courseregspring.repository.CourseRepository;
import org.springframework.stereotype.Service;

@Service
public class CourseService {
    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public boolean enroll(long courseId) {
        Course course = courseRepository.findById(courseId).orElse(null);
        if (course.getLeftSeats() == 0) {
            return false;
        }
        course.decreaseLeftSeats();
        courseRepository.save(course);
        return true;
    }
}
