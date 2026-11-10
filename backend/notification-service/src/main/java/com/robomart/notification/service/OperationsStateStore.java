package com.robomart.notification.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.notification.web.ReconciliationResult;
import tools.jackson.databind.ObjectMapper;

/** Shared operations state and a bounded fanout log, independent of Kafka consumer assignment. */
@Service
public class OperationsStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OperationsStateStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(ReconciliationResult result) {
        jdbc.update("INSERT INTO reconciliation_results(type,result_json,checked_at) VALUES (?,?,?) "
                + "ON CONFLICT(type) DO UPDATE SET result_json=excluded.result_json,checked_at=excluded.checked_at "
                + "WHERE reconciliation_results.checked_at<=excluded.checked_at", result.type(),
                mapper.writeValueAsString(result), Timestamp.from(result.checkedAt()));
    }

    public ReconciliationResult load(String type, ReconciliationResult fallback) {
        var rows = jdbc.query("SELECT result_json FROM reconciliation_results WHERE type=?",
                (rs, n) -> mapper.readValue(rs.getString(1), ReconciliationResult.class), type);
        return rows.isEmpty() ? fallback : rows.getFirst();
    }

    @Transactional
    public void publish(String topic, Object payload) { publish(topic, payload, null); }

    @Transactional
    public void publish(String topic, Object payload, String traceId) {
        // Serialize allocation and commit so a reader never skips a later-committing smaller ID.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(73910351)", Object.class);
        jdbc.update("INSERT INTO admin_broadcasts(topic,payload) VALUES (?,?)", topic, mapper.writeValueAsString(new Envelope(payload, traceId)));
    }

    public long latestId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM admin_broadcasts", Long.class);
    }

    public List<Broadcast> after(long id) {
        return jdbc.query("SELECT id,topic,payload FROM admin_broadcasts WHERE id>? ORDER BY id LIMIT 100",
                (rs, n) -> {
                    var json = mapper.readTree(rs.getString(3));
                    return new Broadcast(rs.getLong(1), rs.getString(2), json.has("data") ? json.get("data") : json,
                            json.hasNonNull("traceId") ? json.get("traceId").asText() : null);
                }, id);
    }

    public int cleanup() {
        return jdbc.update("DELETE FROM admin_broadcasts WHERE id IN (SELECT id FROM admin_broadcasts "
                + "WHERE created_at<now()-interval '1 day' ORDER BY id LIMIT 10000)");
    }

    public record Broadcast(long id, String topic, Object payload, String traceId) { }
    public record Envelope(Object data, String traceId) { }
}
