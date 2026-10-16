package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.Wanted;

public interface WantedRepository extends JpaRepository<Wanted, Long> {

    List<Wanted> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Wanted> findByStatusOrderByCreatedAtDesc(String status);

}
