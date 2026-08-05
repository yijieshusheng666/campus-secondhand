package com.example.demo.repository;

import com.example.demo.entity.GoodsImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GoodsImageRepository extends JpaRepository<GoodsImage, Long> {
    List<GoodsImage> findByGoodsId(Long goodsId);
}