package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.FeedBatch;

import jakarta.persistence.LockModeType;

public interface FeedBatchRepository extends JpaRepository<FeedBatch, Long> {

    Optional<FeedBatch> findByRequestId(String requestId);

    Optional<FeedBatch> findByBatchNo(String batchNo);

    List<FeedBatch> findByStatusOrderByIdDesc(String status);

    List<FeedBatch> findAllByOrderByIdDesc();

    List<FeedBatch> findByIntakeId(Long intakeId);

    List<FeedBatch> findByStationId(Long stationId);

    /** 行锁：撤回 / 完成前锁定投料批，串行化同一批的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from FeedBatch b where b.id = :id")
    Optional<FeedBatch> findByIdForUpdate(@Param("id") Long id);

    /**
     * 行锁读：拿到磅单 / 机组锁之后用它再查一次幂等键。
     * 普通查询在 REPEATABLE READ 下读的是事务快照，可能看不到并发事务刚提交的
     * 同单号投料批；锁定读读最新已提交数据，保证同单号只落一笔。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from FeedBatch b where b.requestId = :requestId")
    Optional<FeedBatch> findByRequestIdForUpdate(@Param("requestId") String requestId);
}
