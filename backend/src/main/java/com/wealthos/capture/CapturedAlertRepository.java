package com.wealthos.capture;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface CapturedAlertRepository extends JpaRepository<CapturedAlert,UUID> {
    Optional<CapturedAlert> findByEventKey(String eventKey);
    List<CapturedAlert> findTop100ByOrderByCreatedAtDesc();
    long countByStatus(String status);
}
