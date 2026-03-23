package com.example.messaging.step1_application_event.repository;

import com.example.messaging.step1_application_event.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
