package com.example.demo.repository;

import com.example.demo.entity.GoodsImage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 商品配图。
 *
 * <p>原有 findByGoodsId 全仓库无人调用（图片只能通过 Goods.images 级联读写），已删除。</p>
 */
public interface GoodsImageRepository extends JpaRepository<GoodsImage, Long> {
}