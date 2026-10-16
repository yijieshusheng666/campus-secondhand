package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.Barter;

public interface BarterRepository extends JpaRepository<Barter, Long> {

    List<Barter> findByRequesterIdOrderByCreatedAtDesc(Long requesterId);

    List<Barter> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    boolean existsByGoodsIdAndRequesterIdAndStatus(Long goodsId, Long requesterId, String status);

}
