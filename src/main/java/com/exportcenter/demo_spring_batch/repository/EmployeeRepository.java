package com.exportcenter.demo_spring_batch.repository;

import com.exportcenter.demo_spring_batch.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findByCompanyId(Long companyId);
}
