package com.example.messaging.step1_application_event.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "step1_points")
public class Point {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;
    private long amount;

    protected Point() {
    }

    public Point(String userId, long amount) {
        this.userId = userId;
        this.amount = amount;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmount() {
        return amount;
    }
}
