package com.example.messaging.step6_kafka.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "step5_orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String productName;
    private long amount;

    protected Order() {
    }

    public Order(String productName, long amount) {
        this.productName = productName;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public String getProductName() {
        return productName;
    }

    public long getAmount() {
        return amount;
    }
}
