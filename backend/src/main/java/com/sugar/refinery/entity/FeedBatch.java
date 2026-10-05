package com.sugar.refinery.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 投料批（投料交接单）。
 *
 * <p>把一张「已验收」磅单拆成一笔或多笔投料批，分配给「运行」中的压榨机组。
 * 每笔必须保留：验收磅单来源、目标机组、计划投料量、交接状态。</p>
 *
 * <p>数量闭环：提交时同一事务内「落投料批 + 扣磅单可投余量 + 增机组占用」；
 * 「待投料」可撤回（两侧余量同时归还）；进入「投料中」后不可撤回、不可改换机组；
 * 完成时记录实际投料量，少于计划的差额释放回两侧余量。</p>
 *
 * <p>幂等：{@code requestId} 是值班员本次交接的单号，重复点击 / 超时重试
 * 都沿用同一单号，数据库唯一键保证只落一笔有效占用。</p>
 */
@Entity
@Table(name = "feed_batch")
@Getter
@Setter
public class FeedBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 投料批号，唯一 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    /** 幂等键：同一交接请求（重复点击 / 超时重试）只落一笔 */
    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    /** 验收磅单来源 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intake_id", nullable = false)
    private CaneIntake intake;

    /** 目标压榨机组 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private PressStation station;

    /** 计划投料量（千克） */
    @Column(name = "plan_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal planKg;

    /** 实际投料量（千克），完成时回填 */
    @Column(name = "actual_kg", precision = 12, scale = 2)
    private BigDecimal actualKg;

    /** 待投料 / 投料中 / 已完成 / 已撤回 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 值班员 */
    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 完成或撤回时间 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
