package com.example.courseregspring.domain;

import jakarta.persistence.*;

@Entity
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(length = 100)
    private String name;

    @Column
    private long leftSeats;

    protected Course() {
    }

    public Course(String name, long leftSeats) {
        this.name = name;
        this.leftSeats = leftSeats;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getLeftSeats() {
        return leftSeats;
    }

    public void decreaseLeftSeats() {
        if (leftSeats <= 0) {
            throw new IllegalStateException("정원이 없습니다.");
        }
        leftSeats--;
    }
}
