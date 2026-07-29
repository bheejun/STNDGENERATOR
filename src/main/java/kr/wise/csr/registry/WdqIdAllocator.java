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

            List<Policy> policies = jdbc.query("""
                    select p.id_prefix,p.number_width,p.last_value,s.wdq_namespace
                    from system_id_policy p join standard_system s on s.id=p.system_id
                    where p.system_id=? and p.id_type=? and p.active for update
                    """, (rs,n) -> new Policy(rs.getString(1),rs.getInt(2),rs.getLong(3),rs.getString(4)),
                    systemId,type.name());
            if (!policies.isEmpty() && policies.getFirst().namespace()!=null
                    && !policies.getFirst().namespace().isBlank()) {
                Policy policy=policies.getFirst();
                long next=policy.lastValue()+1;
                String wdqId=formatPolicy(type,policy,next);
                jdbc.update("update system_id_policy set last_value=?,updated_at=now() where system_id=? and id_type=?",
                        next,systemId,type.name());
                jdbc.update("""
                        insert into id_registry(id_type,logical_key,system_id,wdq_id,first_project_id,sequence_value)
                        values(?,?,?,?,?,?)
                        """,type.name(),logicalKey,systemId,wdqId,projectId,next);
                return wdqId;
            }

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

    private String formatPolicy(WdqIdType type, Policy policy, long sequence) {
        String namespace=policy.namespace();
        String suffix;
        if (type==WdqIdType.DB_CONNECTION) suffix=String.format("%0"+policy.numberWidth()+"d",Long.parseLong(namespace));
        else {
            int remaining=policy.numberWidth()-namespace.length();
            if (remaining<1) throw new IllegalStateException("채번 자릿수가 시스템 네임스페이스보다 짧습니다");
            suffix=namespace+String.format("%0"+remaining+"d",sequence);
        }
        String id=policy.prefix()+suffix;
        if(id.length()>type.maximumLength()) throw new IllegalStateException("WDQ ID 길이 초과: "+id);
        return id;
    }

    public void registerExisting(WdqIdType type, long systemId, String logicalKey,
            String wdqId, Long projectId) {
        requireLogicalKey(logicalKey);
        transactions.executeWithoutResult(status -> {
            List<Policy> policies = jdbc.query("""
                    select p.id_prefix,p.number_width,p.last_value,s.wdq_namespace
                    from system_id_policy p join standard_system s on s.id=p.system_id
                    where p.system_id=? and p.id_type=? and p.active for update
                    """, (rs,n) -> new Policy(rs.getString(1),rs.getInt(2),rs.getLong(3),rs.getString(4)),
                    systemId,type.name());
            Policy policy=policies.isEmpty()?null:policies.getFirst();
            long sequence=policy!=null&&policy.namespace()!=null&&!policy.namespace().isBlank()
                    ? parsePolicy(type,policy,wdqId) : parse(type,wdqId);
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
            if(policy!=null&&policy.namespace()!=null&&!policy.namespace().isBlank())
                jdbc.update("""
                        update system_id_policy set last_value=greatest(last_value,?),updated_at=now()
                        where system_id=? and id_type=?
                        """,sequence,systemId,type.name());
            else
                jdbc.update("insert into id_sequence(id_type, last_value) values (?, ?) "
                        + "on conflict (id_type) do update set last_value=greatest(id_sequence.last_value, excluded.last_value)",
                        type.name(), sequence);
        });
    }

    private long parsePolicy(WdqIdType type,Policy policy,String wdqId) {
        String namespace=policy.namespace();
        String expectedPrefix=policy.prefix();
        if(wdqId==null||!wdqId.startsWith(expectedPrefix)
                ||wdqId.length()!=expectedPrefix.length()+policy.numberWidth())
            throw new IllegalArgumentException("Invalid WDQ ID format for "+type);
        String numeric=wdqId.substring(expectedPrefix.length());
        if(!numeric.matches("[0-9]+"))
            throw new IllegalArgumentException("Invalid WDQ ID format for "+type);
        if(type==WdqIdType.DB_CONNECTION) {
            String expected=String.format("%0"+policy.numberWidth()+"d",Long.parseLong(namespace));
            if(!numeric.equals(expected))
                throw new IllegalArgumentException("WDQ ID가 시스템 채번 영역 "+namespace+"에 속하지 않습니다: "+wdqId);
            return 1;
        }
        if(!numeric.startsWith(namespace))
            throw new IllegalArgumentException("WDQ ID가 시스템 채번 영역 "+namespace+"로 시작하지 않습니다: "+wdqId);
        long sequence=Long.parseLong(numeric.substring(namespace.length()));
        if(sequence<1) throw new IllegalArgumentException("Invalid WDQ ID sequence for "+type);
        return sequence;
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
    private record Policy(String prefix,int numberWidth,long lastValue,String namespace) {}
}
