package com.example.omp.payment.repository;

import com.example.omp.payment.domain.RefundReconciliationAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundReconciliationAuditRepository
        extends JpaRepository<RefundReconciliationAudit, UUID> {
    List<RefundReconciliationAudit> findTop20ByRefund_IdOrderByCreatedAtDescIdDesc(UUID refundId);
}
