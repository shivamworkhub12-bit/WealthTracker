package com.wealthos.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    boolean existsByFingerprint(String fingerprint);
    Optional<Transaction> findByFingerprint(String fingerprint);
    List<Transaction> findAllByDateBetweenOrderByDateDesc(LocalDate start, LocalDate end);
}
