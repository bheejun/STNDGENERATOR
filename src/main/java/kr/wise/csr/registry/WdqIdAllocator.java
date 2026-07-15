package kr.wise.csr.registry;

import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WdqIdAllocator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public WdqIdAllocator(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public String format(WdqIdType type, long sequence) {
        Objects.requireNonNull(type, "type");
        if (sequence < 1 || sequence > type.maximumSequence()) {
            throw new IllegalStateException("WDQ ID sequence overflow for " + type);
        }
        String id = type.prefix() + String.format("%0" + type.digits() + "d", sequence);
        if (id.length() > type.maximumLength()) {
            throw new IllegalStateException("WDQ ID exceeds the WDQ column length for " + type);
        }
        return id;
    }

    public String allocate(WdqIdType type, long systemId, String logicalKey, Long projectId) {
        requireLogicalKey(logicalKey);
        return transactions.execute(status -> {
            String existing = findExisting(type, systemId, logicalKey);
            if (existing != null) return existing;

            jdbc.update("insert into id_sequence(id_type, last_value) values (?, 0) on conflict do nothing",
                    type.name());
            Long current = jdbc.queryForObject(
                    "select last_value from id_sequence where id_type=? for update", Long.class, type.name());
            existing = findExisting(type, systemId, logicalKey);
            if (existing != null) return existing;

            long next = current + 1;
            String wdqId = format(type, next);
            jdbc.update("update id_sequence set last_value=? where id_type=?", next, type.name());
            jdbc.update("""
                    insert into id_registry(id_type, logical_key, system_id, wdq_id, first_project_id, sequence_value)
                    values (?, ?, ?, ?, ?, ?)
                    """, type.name(), logicalKey, systemId, wdqId, projectId, next);
            return wdqId;
        });
    }

    public void registerExisting(WdqIdType type, long systemId, String logicalKey,
            String wdqId, Long projectId) {
        requireLogicalKey(logicalKey);
        long sequence = parse(type, wdqId);
        transactions.executeWithoutResult(status -> {
            List<Long> owners = jdbc.queryForList(
                    "select system_id from id_registry where wdq_id=?", Long.class, wdqId);
            if (!owners.isEmpty() && owners.getFirst() != systemId) {
                throw new IllegalArgumentException("WDQ ID is owned by another system: " + wdqId);
            }
            String existing = findExisting(type, systemId, logicalKey);
            if (existing != null && !existing.equals(wdqId)) {
                throw new IllegalArgumentException("Logical object already has another WDQ ID");
            }
            if (owners.isEmpty()) {
                jdbc.update("""
                        insert into id_registry(id_type, logical_key, system_id, wdq_id, first_project_id, sequence_value)
                        values (?, ?, ?, ?, ?, ?)
                        """, type.name(), logicalKey, systemId, wdqId, projectId, sequence);
            }
            jdbc.update("insert into id_sequence(id_type, last_value) values (?, ?) "
                    + "on conflict (id_type) do update set last_value=greatest(id_sequence.last_value, excluded.last_value)",
                    type.name(), sequence);
        });
    }

    private String findExisting(WdqIdType type, long systemId, String logicalKey) {
        List<String> ids = jdbc.queryForList("""
                select wdq_id from id_registry where id_type=? and system_id=? and logical_key=?
                """, String.class, type.name(), systemId, logicalKey);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long parse(WdqIdType type, String wdqId) {
        if (wdqId == null || wdqId.length() != type.prefix().length() + type.digits()
                || !wdqId.startsWith(type.prefix())) {
            throw new IllegalArgumentException("Invalid WDQ ID format for " + type);
        }
        try {
            long value = Long.parseLong(wdqId.substring(type.prefix().length()));
            format(type, value);
            return value;
        } catch (NumberFormatException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid WDQ ID format for " + type, e);
        }
    }

    private void requireLogicalKey(String logicalKey) {
        if (logicalKey == null || logicalKey.isBlank()) {
            throw new IllegalArgumentException("logicalKey is required");
        }
    }
}
