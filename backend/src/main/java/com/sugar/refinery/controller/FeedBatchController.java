package com.sugar.refinery.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.dto.FeedBatchCreateRequest;
import com.sugar.refinery.dto.FeedBatchView;
import com.sugar.refinery.service.FeedBatchService;

/**
 * 投料交接接口。
 *
 * <p>提交交接是幂等的：值班员重复点击、超时重试都沿用同一交接单号，
 * 服务端凭单号只落一笔有效占用；命中重复时返回原投料批并明确告知未重复扣减。</p>
 */
@RestController
@RequestMapping("/api/feed-batches")
public class FeedBatchController {

    private final FeedBatchService feedService;

    public FeedBatchController(FeedBatchService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    public ApiResponse<List<FeedBatchView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(feedService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeedBatchView> get(@PathVariable Long id) {
        return ApiResponse.success(feedService.get(id));
    }

    /**
     * 提交交接：同事务扣磅单余量 + 增机组占用 + 落投料批。
     * 失败整体回滚，值班员可安全重试。
     */
    @PostMapping
    public ApiResponse<FeedBatchView> create(@RequestBody FeedBatchCreateRequest req) {
        try {
            FeedBatchView view = feedService.create(req);
            if (view.idempotentHit()) {
                return ApiResponse.success("该交接单号已受理过，系统未重复扣减（安全重试生效）", view);
            }
            return ApiResponse.success("交接已受理：磅单余量与机组占用已同步扣减", view);
        } catch (DataIntegrityViolationException e) {
            // 兜底：并发下同一交接单号撞唯一键——有效占用已由先到的请求落下，
            // 本次未产生任何扣减，返回原投料批即可
            String requestId = req == null ? null : req.requestId();
            return feedService.findByRequestId(requestId)
                    .map(v -> ApiResponse.success("该交接单号已受理过，系统未重复扣减（安全重试生效）", v))
                    .orElseThrow(() -> e);
        }
    }

    /** 开始投料：待投料 → 投料中（之后不可撤回、不可改换机组） */
    @PostMapping("/{id}/start")
    public ApiResponse<FeedBatchView> start(@PathVariable Long id) {
        return ApiResponse.success("已开始投料", feedService.start(id));
    }

    /** 撤回：仅待投料可撤回，同时归还磅单余量与机组能力 */
    @PostMapping("/{id}/withdraw")
    public ApiResponse<FeedBatchView> withdraw(@PathVariable Long id) {
        return ApiResponse.success("已撤回：磅单余量与机组能力已同步归还", feedService.withdraw(id));
    }

    /** 完成：记录实际投料量，差额释放回两侧余量 */
    @PostMapping("/{id}/complete")
    public ApiResponse<FeedBatchView> complete(@PathVariable Long id, @RequestParam BigDecimal actualKg) {
        return ApiResponse.success("已完成投料，差额已释放回两侧余量", feedService.complete(id, actualKg));
    }
}
