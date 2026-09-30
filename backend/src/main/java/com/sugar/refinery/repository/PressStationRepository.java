package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sugar.refinery.entity.PressStation;

public interface PressStationRepository extends JpaRepository<PressStation, Long> {

    Optional<PressStation> findByStationCode(String stationCode);

    List<PressStation> findByStatus(String status);
}
