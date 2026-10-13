package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.demo.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByRevieweeIdOrderByCreatedAtDesc(Long revieweeId);

    List<Review> findByOrderId(Long orderId);

    /** 同一订单同一个人只能评价一次 */
    boolean existsByOrderIdAndReviewerId(Long orderId, Long reviewerId);

    long countByRevieweeId(Long revieweeId);

    @Query("select avg(r.score) from Review r where r.revieweeId = :userId")
    Double avgScoreByRevieweeId(@Param("userId") Long userId);
}
