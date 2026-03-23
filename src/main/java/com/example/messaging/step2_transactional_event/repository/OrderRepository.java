package com.example.messaging.step2_transactional_event.repository;

import com.example.messaging.step2_transactional_event.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
