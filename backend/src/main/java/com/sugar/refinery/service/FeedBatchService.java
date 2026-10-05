package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.dto.FeedBatchCreateRequest;
import com.sugar.refinery.dto.FeedBatchView;
import com.sugar.refinery.entity.CaneIntake;
import com.sugar.refinery.entity.FeedBatch;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.CaneIntakeRepository;
import com.sugar.refinery.repository.FeedBatchRepository;
import com.sugar.refinery.repository.PressStationRepository;

/**
 * 投料交接业务：把已验收磅单拆成投料批并分配给运行中的压榨机组。
 *
 * <p>核心规则：
 * ① 只有「已验收」且可投余量大于 0 的磅单能进入交接，机组必须是「运行」；
 * ② 计划量不得超过磅单尚未分配的净重，也不得让机组累计占用超过设计处理量，
 *    两侧任一不足都直接拒绝，不按较大值放行；
 * ③ 提交 = 同一事务内「落投料批 + 扣磅单余量 + 增机组占用」，任一步失败整体回滚，
 *    不留半成品状态，值班员可安全重试；
 * ④ 幂等：交接单号（requestId）唯一，重复点击 / 超时重试只落一笔有效占用；
 * ⑤ 「待投料」可撤回（两侧余量同时归还）；「投料中」不可撤回、不可改换机组；
 *    完成按实际量结算，差额释放回两侧余量。</p>
 *
 * <p>并发：所有改账路径统一按「磅单 → 机组 → 投料批」的顺序加悲观行锁，
 * 同一磅单 / 同一机组的并发分配被串行化，不会超分，也不会死锁。</p>
 */
@Service
public class FeedBatchService {

    private final FeedBatchRepository feedRepository;
    private final CaneIntakeRepository intakeRepository;
    private final PressStationRepository pressRepository;

    public FeedBatchService(FeedBatchRepository feedRepository,
                            CaneIntakeRepository intakeRepository,
                            PressStationRepository pressRepository) {
        this.feedRepository = feedRepository;
        this.intakeRepository = intakeRepository;
        this.pressRepository = pressRepository;
    }

    @Transactional(readOnly = true)
    public List<FeedBatchView> list(String status) {
        List<FeedBatch> batches = (status != null && !status.isBlank())
                ? feedRepository.findByStatusOrderByIdDesc(status)
                : feedRepository.findAllByOrderByIdDesc();
        return batches.stream().map(FeedBatchView::of).toList();
    }

    @Transactional(readOnly = true)
    public FeedBatchView get(Long id) {
        return FeedBatchView.of(feedRepository.findById(id)
                .orElseThrow(() -> new BizException("投料批不存在：" + id)));
    }

    /** 按交接单号查（幂等命中时给值班员返回原投料批） */
    @Transactional(readOnly = true)
    public Optional<FeedBatchView> findByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return feedRepository.findByRequestId(requestId.trim()).map(FeedBatchView::of);
    }

    /**
     * 提交交接：同一事务内落投料批、扣磅单可投余量、增机组占用。
     * 任一校验失败或落库失败都会整体回滚，不产生只有一部分的半成品状态。
     */
    @Transactional
    public FeedBatchView create(FeedBatchCreateRequest req) {
        if (req == null) {
            throw new BizException("请求体不能为空");
        }
        String requestId = req.requestId() == null ? "" : req.requestId().trim();
        if (requestId.isEmpty()) {
            throw new BizException("交接单号（requestId）不能为空");
        }
        if (req.intakeId() == null) {
            throw new BizException("必须选择来源磅单");
        }
        if (req.stationId() == null) {
            throw new BizException("必须选择目标机组");
        }
        BigDecimal plan = checkedAmount(req.planKg(), "计划投料量");

        // 幂等快路径：同一交接单号已落账，直接返回原投料批（重复点击 / 超时重试）
        Optional<FeedBatch> existed = feedRepository.findByRequestId(requestId);
        if (existed.isPresent()) {
            return FeedBatchView.hit(existed.get());
        }

        // 固定加锁顺序：先磅单、后机组。所有改账路径同序，避免相互死锁
        CaneIntake intake = intakeRepository.findByIdForUpdate(req.intakeId())
                .orElseThrow(() -> new BizException("磅单不存在：" + req.intakeId()));
        PressStation station = pressRepository.findByIdForUpdate(req.stationId())
                .orElseThrow(() -> new BizException("压榨机组不存在：" + req.stationId()));

        // 拿锁后再查一次单号（锁定读，看最新已提交数据）：
        // 两名值班员 / 两次重试并发到同一磅单时，后到者在这里看到先到者已落的那一笔
        Optional<FeedBatch> again = feedRepository.findByRequestIdForUpdate(requestId);
        if (again.isPresent()) {
            return FeedBatchView.hit(again.get());
        }

        // 规则①：未验收、已拒收或余量为零的磅单不能进入交接
        if (!"已验收".equals(intake.getStatus())) {
            throw new BizException("只有「已验收」的磅单能进入投料交接，当前状态：" + intake.getStatus());
        }
        BigDecimal remaining = intake.getNetKg().subtract(intake.getAllocatedKg());
        if (remaining.signum() <= 0) {
            throw new BizException("磅单 " + intake.getTicketNo() + " 可投余量已为 0，不能再交接");
        }
        // 规则② 磅单侧：计划量不得超过尚未分配的净重
        if (plan.compareTo(remaining) > 0) {
            throw new BizException("计划投料量 " + plan + " kg 超过磅单可投余量 " + remaining
                    + " kg，两侧任一不足都不能交接");
        }

        // 规则① 机组侧：只分配给运行中的机组
        if (!"运行".equals(station.getStatus())) {
            throw new BizException("只能分配给「运行」中的机组，" + station.getStationName()
                    + " 当前状态：" + station.getStatus());
        }
        // 规则② 机组侧：累计占用不得超过设计处理量（统一折算成千克比较，不丢精度）
        BigDecimal capacityKg = station.getDesignCapacityTph().multiply(BigDecimal.valueOf(1000));
        BigDecimal freeKg = capacityKg.subtract(station.getOccupiedKg());
        if (plan.compareTo(freeKg) > 0) {
            throw new BizException("机组剩余处理能力不足：" + station.getStationName() + " 剩余 "
                    + freeKg + " kg，本次计划 " + plan + " kg，两侧任一不足都不能交接");
        }

        // 规则③：三处落账同事务——投料批、磅单扣减、机组占用，要么全成要么全回滚
        intake.setAllocatedKg(intake.getAllocatedKg().add(plan));
        station.setOccupiedKg(station.getOccupiedKg().add(plan));

        FeedBatch batch = new FeedBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setRequestId(requestId);
        batch.setIntake(intake);
        batch.setStation(station);
        batch.setPlanKg(plan);
        batch.setStatus("待投料");
        batch.setCreatedBy(req.createdBy() == null || req.createdBy().isBlank()
                ? "当班值班员" : req.createdBy().trim());
        batch.setCreatedAt(LocalDateTime.now());

        intakeRepository.save(intake);
        pressRepository.save(station);
        return FeedBatchView.of(feedRepository.save(batch));
    }

    /** 开始投料：待投料 → 投料中。进入投料中后不可撤回、不可改换机组 */
    @Transactional
    public FeedBatchView start(Long id) {
        FeedBatch batch = feedRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("投料批不存在：" + id));
        if (!Dict.canTransferFeed(batch.getStatus(), "投料中")) {
            throw new BizException("只有「待投料」的投料批能开始投料，当前状态：" + batch.getStatus());
        }
        batch.setStatus("投料中");
        batch.setStartedAt(LocalDateTime.now());
        return FeedBatchView.of(feedRepository.save(batch));
    }

    /** 撤回：仅「待投料」可撤回；同一事务内同时归还磅单余量与机组能力 */
    @Transactional
    public FeedBatchView withdraw(Long id) {
        FeedBatch probe = getBatch(id);
        // 与 create 相同的加锁顺序：磅单 → 机组 → 投料批
        CaneIntake intake = intakeRepository.findByIdForUpdate(probe.getIntake().getId())
                .orElseThrow(() -> new BizException("磅单不存在：" + probe.getIntake().getId()));
        PressStation station = pressRepository.findByIdForUpdate(probe.getStation().getId())
                .orElseThrow(() -> new BizException("压榨机组不存在：" + probe.getStation().getId()));
        FeedBatch batch = feedRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("投料批不存在：" + id));

        if (!Dict.canTransferFeed(batch.getStatus(), "已撤回")) {
            throw new BizException("只有「待投料」的投料批能撤回，当前状态：" + batch.getStatus()
                    + "；进入「投料中」后不能撤回或改换机组");
        }

        // 同一事务内：撤批 + 归还磅单余量 + 归还机组能力
        intake.setAllocatedKg(intake.getAllocatedKg().subtract(batch.getPlanKg()));
        station.setOccupiedKg(station.getOccupiedKg().subtract(batch.getPlanKg()));
        batch.setStatus("已撤回");
        batch.setFinishedAt(LocalDateTime.now());

        intakeRepository.save(intake);
        pressRepository.save(station);
        return FeedBatchView.of(feedRepository.save(batch));
    }

    /** 完成：仅「投料中」可完成；记录实际量，少于计划的差额释放回两侧余量 */
    @Transactional
    public FeedBatchView complete(Long id, BigDecimal actualKg) {
        BigDecimal actual = checkedAmount(actualKg, "实际投料量");
        FeedBatch probe = getBatch(id);
        CaneIntake intake = intakeRepository.findByIdForUpdate(probe.getIntake().getId())
                .orElseThrow(() -> new BizException("磅单不存在：" + probe.getIntake().getId()));
        PressStation station = pressRepository.findByIdForUpdate(probe.getStation().getId())
                .orElseThrow(() -> new BizException("压榨机组不存在：" + probe.getStation().getId()));
        FeedBatch batch = feedRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("投料批不存在：" + id));

        if (!Dict.canTransferFeed(batch.getStatus(), "已完成")) {
            throw new BizException("只有「投料中」的投料批能完成，当前状态：" + batch.getStatus());
        }
        if (actual.compareTo(batch.getPlanKg()) > 0) {
            throw new BizException("实际投料量 " + actual + " kg 超过计划量 "
                    + batch.getPlanKg() + " kg，请核对后重试");
        }

        // 差额 = 计划 - 实际，同一事务内释放回磅单余量与机组能力两侧
        BigDecimal shortfall = batch.getPlanKg().subtract(actual);
        intake.setAllocatedKg(intake.getAllocatedKg().subtract(shortfall));
        station.setOccupiedKg(station.getOccupiedKg().subtract(shortfall));
        batch.setActualKg(actual);
        batch.setStatus("已完成");
        batch.setFinishedAt(LocalDateTime.now());

        intakeRepository.save(intake);
        pressRepository.save(station);
        return FeedBatchView.of(feedRepository.save(batch));
    }

    private FeedBatch getBatch(Long id) {
        return feedRepository.findById(id)
                .orElseThrow(() -> new BizException("投料批不存在：" + id));
    }

    /** 数量必须为正且最多两位小数（与 DECIMAL(12,2) 对齐，避免落库时被静默四舍五入） */
    private BigDecimal checkedAmount(BigDecimal amount, String label) {
        if (amount == null || amount.signum() <= 0) {
            throw new BizException(label + "必须大于 0");
        }
        if (amount.scale() > 2) {
            throw new BizException(label + "最多两位小数");
        }
        return amount;
    }

    private String nextBatchNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "FB-" + ts + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
