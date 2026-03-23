package com.example.messaging.step1_application_event.repository;

import com.example.messaging.step1_application_event.domain.Point;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointRepository extends JpaRepository<Point, Long> {
    Optional<Point> findByUserId(String userId);
}
