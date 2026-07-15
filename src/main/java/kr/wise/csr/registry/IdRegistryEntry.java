package kr.wise.csr.registry;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "id_registry")
public class IdRegistryEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    private WdqIdType idType;
    private String logicalKey;
    private long systemId;
    private String wdqId;
    private Long firstProjectId;
    private long sequenceValue;

    protected IdRegistryEntry() {}
}
