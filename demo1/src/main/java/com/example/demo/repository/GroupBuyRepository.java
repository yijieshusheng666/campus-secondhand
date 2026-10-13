package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.GroupBuy;

public interface GroupBuyRepository extends JpaRepository<GroupBuy, Long> {

    List<GroupBuy> findByStatusOrderByCreatedAtDesc(String status);

    List<GroupBuy> findByGoodsIdAndStatusOrderByCreatedAtDesc(Long goodsId, String status);

    List<GroupBuy> findByBuildingAndStatusOrderByCreatedAtDesc(String building, String status);
}
