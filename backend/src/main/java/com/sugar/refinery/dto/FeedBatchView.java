package com.sugar.refinery.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sugar.refinery.entity.FeedBatch;

/**
 * 投料批视图：把磅单来源与目标机组摊平，避免懒加载泄漏到序列化层
 * （open-in-view 关闭，控制器返回时事务已结束）。
 *
 * <p>{@code idempotentHit} 为 true 表示本次是重复请求（重复点击 / 超时重试），
 * 返回的是早已落账的原投料批，没有重复扣减。</p>
 */
public record FeedBatchView(
        Long id,
        String batchNo,
        String requestId,
        Long intakeId,
        String ticketNo,
        String supplier,
        Long stationId,
        String stationCode,
        String stationName,
        BigDecimal planKg,
        BigDecimal actualKg,
        String status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean idempotentHit) {

    public static FeedBatchView of(FeedBatch b) {
        return of(b, false);
    }

    /** 幂等命中：同一交接单号已受理过，返回原投料批 */
    public static FeedBatchView hit(FeedBatch b) {
        return of(b, true);
    }

    private static FeedBatchView of(FeedBatch b, boolean hit) {
        return new FeedBatchView(
                b.getId(),
                b.getBatchNo(),
                b.getRequestId(),
                b.getIntake().getId(),
                b.getIntake().getTicketNo(),
                b.getIntake().getSupplier(),
                b.getStation().getId(),
                b.getStation().getStationCode(),
                b.getStation().getStationName(),
                b.getPlanKg(),
                b.getActualKg(),
                b.getStatus(),
                b.getCreatedBy(),
                b.getCreatedAt(),
                b.getStartedAt(),
                b.getFinishedAt(),
                hit);
    }
}
