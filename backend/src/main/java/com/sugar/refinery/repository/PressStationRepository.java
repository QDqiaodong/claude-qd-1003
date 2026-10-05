package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.PressStation;

import jakarta.persistence.LockModeType;

public interface PressStationRepository extends JpaRepository<PressStation, Long> {

    Optional<PressStation> findByStationCode(String stationCode);

    List<PressStation> findByStatus(String status);

    /** 行锁：投料交接 / 撤回 / 完成时锁定机组，串行化同一机组的并发占用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PressStation p where p.id = :id")
    Optional<PressStation> findByIdForUpdate(@Param("id") Long id);
}
