package com.hmrag.backend.repository;

import com.hmrag.backend.domain.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {
    Optional<DataSource> findByRootPath(String rootPath);
}
