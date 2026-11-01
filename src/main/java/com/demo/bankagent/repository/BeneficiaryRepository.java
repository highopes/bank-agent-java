package com.demo.bankagent.repository;

import com.demo.bankagent.domain.Beneficiary;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, String> {

    List<Beneficiary> findByCustomerIdOrderByIdAsc(String customerId);

    Optional<Beneficiary> findByIdAndCustomerId(String id, String customerId);
}
