package com.demo.bankagent.repository;

import com.demo.bankagent.domain.TransferDraft;
import com.demo.bankagent.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferDraftRepository extends JpaRepository<TransferDraft, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TransferDraft d where d.id = :id")
    Optional<TransferDraft> findByIdForUpdate(@Param("id") String id);

    List<TransferDraft> findTop10ByConversationIdOrderByCreatedAtDesc(String conversationId);

    List<TransferDraft> findByConversationIdAndStatusOrderByCreatedAtDesc(String conversationId, TransferStatus status);
}
