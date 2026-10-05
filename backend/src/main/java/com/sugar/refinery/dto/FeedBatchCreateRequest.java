package com.sugar.refinery.dto;

import java.math.BigDecimal;

/**
 * 投料交接请求：值班员把一张已验收磅单的一部分（或全部）交接给一台运行机组。
 *
 * <p>{@code requestId} 是本次交接的单号，由前端在提交前生成；重复点击、
 * 超时重试都沿用同一单号，服务端凭它保证只落一笔有效占用。</p>
 */
public record FeedBatchCreateRequest(
        String requestId,
        Long intakeId,
        Long stationId,
        BigDecimal planKg,
        String createdBy) {
}
