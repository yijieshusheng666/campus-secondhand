package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.Dispute;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /** 待处理纠纷：按提交时间倒序（最新提交优先处理）。 */
    List<Dispute> findByStatusOrderByCreatedAtDesc(String status);

    /** 某人的全部纠纷（他发起的 + 他被指为对方的），按提交时间倒序。 */
    List<Dispute> findByInitiatorIdOrRespondentIdOrderByCreatedAtDesc(Long initiatorId, Long respondentId);

    /** 同一订单不允许同时存在两条未处理纠纷 */
    boolean existsByOrderIdAndStatus(Long orderId, String status);
}
