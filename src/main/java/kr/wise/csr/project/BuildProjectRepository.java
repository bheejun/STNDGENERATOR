package kr.wise.csr.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildProjectRepository extends JpaRepository<BuildProject, Long> {
    Optional<BuildProject> findBySystemIdAndTargetYear(long systemId, int targetYear);
}
