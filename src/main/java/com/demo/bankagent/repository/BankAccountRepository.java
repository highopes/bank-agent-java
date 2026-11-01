package com.demo.bankagent.repository;

import com.demo.bankagent.domain.AccountType;
import com.demo.bankagent.domain.BankAccount;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankAccountRepository extends JpaRepository<BankAccount, String> {

    List<BankAccount> findByCustomerIdOrderByIdAsc(String customerId);

    Optional<BankAccount> findFirstByCustomerIdAndAccountType(String customerId, AccountType accountType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BankAccount a where a.id = :id")
    Optional<BankAccount> findByIdForUpdate(@Param("id") String id);
}
