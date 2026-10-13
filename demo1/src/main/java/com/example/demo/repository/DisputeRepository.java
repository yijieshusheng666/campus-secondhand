package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.Dispute;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByStatusOrderByCreatedAtAsc(String status);

    List<Dispute> findByStatusOrderByHandledAtDesc(String status);

    List<Dispute> findByInitiatorIdOrRespondentIdOrderByCreatedAtDesc(Long initiatorId, Long respondentId);

    long countByStatus(String status);

    /** 同一订单不允许同时存在两条未处理纠纷 */
    boolean existsByOrderIdAndStatus(Long orderId, String status);
}
