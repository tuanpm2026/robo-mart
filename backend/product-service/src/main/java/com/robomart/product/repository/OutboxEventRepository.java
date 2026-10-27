package com.robomart.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.robomart.product.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
