package com.demo.bankagent.repository;

import com.demo.bankagent.domain.DemoCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoCustomerRepository extends JpaRepository<DemoCustomer, String> {
}
