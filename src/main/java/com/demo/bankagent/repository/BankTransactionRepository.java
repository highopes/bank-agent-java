package com.demo.bankagent.repository;

import com.demo.bankagent.domain.BankTransaction;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long>,
        JpaSpecificationExecutor<BankTransaction> {

    @Query("select t from BankTransaction t where t.accountId in :accountIds order by t.bookedAt desc, t.id desc")
    List<BankTransaction> findRecent(@Param("accountIds") Collection<String> accountIds, Pageable pageable);

    @Query("""
            select t from BankTransaction t
            where t.accountId in :accountIds and t.bookedAt >= :from and t.bookedAt < :to
            order by t.bookedAt desc, t.id desc""")
    List<BankTransaction> findInRange(@Param("accountIds") Collection<String> accountIds,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    long countByReference(String reference);
}
