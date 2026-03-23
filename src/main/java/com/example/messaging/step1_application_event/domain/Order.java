package com.example.messaging.step1_application_event.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "step1_orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;
    private long amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    protected Order() {
    }

    public Order(String userId, long amount) {
        this.userId = userId;
        this.amount = amount;
        this.status = OrderStatus.CREATED;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
