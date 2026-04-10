package com.robomart.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.notification.entity.FailedEvent;
import com.robomart.notification.repository.FailedEventRepository;

@Service
public class FailedEventService {

    private static final Logger log = LoggerFactory.getLogger(FailedEventService.class);

    private final FailedEventRepository failedEventRepository;

    public FailedEventService(FailedEventRepository failedEventRepository) {
        this.failedEventRepository = failedEventRepository;
    }

    @Transactional
    public boolean retryEvent(Long id) {
        FailedEvent event = failedEventRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
        if (!"PENDING".equals(event.getStatus())) {
            throw new IllegalStateException("Event already processed");
        }
        log.info("Retrying event {}", id);
        event.setStatus("RESOLVED");
        event.setLastAttemptedAt(Instant.now());
        failedEventRepository.save(event);
        return true;
    }

    @Transactional(readOnly = true)
    public Page<FailedEvent> listEvents(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("firstFailedAt").descending());
        return failedEventRepository.findByStatusNot("RESOLVED", pageRequest);
    }

    @Transactional
    public int retryAll() {
        List<FailedEvent> pending = failedEventRepository.findByStatus("PENDING",
                PageRequest.of(0, 500, Sort.by("firstFailedAt").ascending()));
        for (FailedEvent event : pending) {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setStatus("RESOLVED");
            event.setLastAttemptedAt(Instant.now());
        }
        failedEventRepository.saveAll(pending);
        return pending.size();
    }
}
