package com.demo.bankagent.repository;

import com.demo.bankagent.domain.PaymentAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, Long> {

    List<PaymentAudit> findTop10ByConversationIdOrderByCreatedAtDescIdDesc(String conversationId);

    List<PaymentAudit> findByDraftIdOrderByIdAsc(String draftId);
}
