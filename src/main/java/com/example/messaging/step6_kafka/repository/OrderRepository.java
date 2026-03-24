package com.example.messaging.step6_kafka.repository;

import com.example.messaging.step6_kafka.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
