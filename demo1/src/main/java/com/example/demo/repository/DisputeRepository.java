package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.Dispute;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /** 待处理纠纷：按提交时间倒序（最新提交优先处理）。 */
    List<Dispute> findByStatusOrderByCreatedAtDesc(String status);

    /** 历史已裁定纠纷：按裁定时间倒序（最近结果先看到）。 */
    List<Dispute> findByStatusOrderByHandledAtDesc(String status);

    List<Dispute> findByInitiatorIdOrRespondentIdOrderByCreatedAtDesc(Long initiatorId, Long respondentId);

    long countByStatus(String status);

    /** 同一订单不允许同时存在两条未处理纠纷 */
    boolean existsByOrderIdAndStatus(Long orderId, String status);
}
