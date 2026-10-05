package com.sugar.refinery.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 压榨机组。
 *
 * <p>甘蔗压榨出蔗汁的第一道主力设备；负荷超过设计处理量即视为超载，
 * 不允许再往上压。状态：运行 ↔ 停机；检修只能从运行/停机进，检修完回停机。</p>
 */
@Entity
@Table(name = "press_station")
@Getter
@Setter
public class PressStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机组编号，唯一 */
    @Column(name = "station_code", nullable = false, unique = true, length = 24)
    private String stationCode;

    @Column(name = "station_name", nullable = false, length = 64)
    private String stationName;

    /** 设计处理量（吨/小时） */
    @Column(name = "design_capacity_tph", nullable = false, precision = 8, scale = 2)
    private BigDecimal designCapacityTph;

    /** 当前负荷（吨/小时） */
    @Column(name = "current_load_tph", nullable = false, precision = 8, scale = 2)
    private BigDecimal currentLoadTph;

    /**
     * 投料批占用（千克甘蔗）：投料批「待投料 / 投料中」按计划量占用，
     * 「已完成」按实际量留存，「已撤回」全量归还。
     * 累计占用不得超过设计处理量（比较时统一折算成千克，避免精度丢失）。
     */
    @Column(name = "occupied_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal occupiedKg = BigDecimal.ZERO;

    /** 运行 / 停机 / 检修 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_maintain_at")
    private LocalDateTime lastMaintainAt;

    /** 负荷率（%），派生字段 */
    @Transient
    public Double getLoadRate() {
        if (designCapacityTph == null || designCapacityTph.signum() <= 0 || currentLoadTph == null) {
            return 0.0;
        }
        return currentLoadTph.multiply(BigDecimal.valueOf(100))
                .divide(designCapacityTph, 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 是否超载，派生字段 */
    @Transient
    public boolean isOverload() {
        if (designCapacityTph == null || currentLoadTph == null) {
            return false;
        }
        return currentLoadTph.compareTo(designCapacityTph) > 0;
    }

    /** 投料批已占用量（吨），派生字段 */
    @Transient
    public BigDecimal getOccupiedTon() {
        return occupiedKg == null ? BigDecimal.ZERO
                : occupiedKg.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }

    /** 剩余处理能力（吨/小时）= 设计处理量 - 投料批已占用，派生字段 */
    @Transient
    public BigDecimal getRemainingCapacityTph() {
        if (designCapacityTph == null) {
            return BigDecimal.ZERO;
        }
        return designCapacityTph.subtract(getOccupiedTon());
    }
}
