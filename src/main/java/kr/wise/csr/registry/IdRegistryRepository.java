package kr.wise.csr.registry;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdRegistryRepository extends JpaRepository<IdRegistryEntry, Long> {
    Optional<IdRegistryEntry> findByIdTypeAndSystemIdAndLogicalKey(
            WdqIdType idType, long systemId, String logicalKey);
}
