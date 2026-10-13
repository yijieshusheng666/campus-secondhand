package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 宿舍楼拼单（C3）。同一栋楼的同学凑人数，人满成团。
 * 成员用 @ElementCollection 映射到中间表，省一个实体。
 */
@Entity
@Table(name = "group_buys")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class GroupBuy {

    public static final String STATUS_OPEN = "拼单中";
    public static final String STATUS_DONE = "已成团";
    public static final String STATUS_CANCELLED = "已取消";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goods_id")
    private Long goodsId;

    private String goodsTitle;

    @Column(name = "initiator_id")
    private Long initiatorId;

    private String initiatorName;

    /** 宿舍楼，例如「12 栋」；拼单按楼聚合，只有同楼的人能加入 */
    private String building;

    /** 成团人数（含发起人） */
    @Column(name = "target_count")
    private Integer targetCount;

    private String status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_buy_members", joinColumns = @JoinColumn(name = "group_buy_id"))
    @Column(name = "user_id")
    private Set<Long> memberIds = new LinkedHashSet<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = STATUS_OPEN;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(Long goodsId) {
        this.goodsId = goodsId;
    }

    public String getGoodsTitle() {
        return goodsTitle;
    }

    public void setGoodsTitle(String goodsTitle) {
        this.goodsTitle = goodsTitle;
    }

    public Long getInitiatorId() {
        return initiatorId;
    }

    public void setInitiatorId(Long initiatorId) {
        this.initiatorId = initiatorId;
    }

    public String getInitiatorName() {
        return initiatorName;
    }

    public void setInitiatorName(String initiatorName) {
        this.initiatorName = initiatorName;
    }

    public String getBuilding() {
        return building;
    }

    public void setBuilding(String building) {
        this.building = building;
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(Integer targetCount) {
        this.targetCount = targetCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Set<Long> getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(Set<Long> memberIds) {
        this.memberIds = memberIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
