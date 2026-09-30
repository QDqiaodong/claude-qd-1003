package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sugar.refinery.entity.CaneIntake;

public interface CaneIntakeRepository extends JpaRepository<CaneIntake, Long> {

    Optional<CaneIntake> findByTicketNo(String ticketNo);

    List<CaneIntake> findByStatus(String status);

    List<CaneIntake> findByResult(String result);

    List<CaneIntake> findBySupplier(String supplier);
}
