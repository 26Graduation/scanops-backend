package com.scanops.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    List<ScanJob> findByOwnerEmailOrderByCreatedAtDesc(String ownerEmail);
}
