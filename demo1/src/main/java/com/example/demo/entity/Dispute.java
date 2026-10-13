package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 交易纠纷（B4）。买家对在途订单申请平台介入，由管理员裁定。
 * 裁定只有两种结局：支持买家退款，或驳回诉求并放款给卖家。
 */
@Entity
@Table(name = "disputes")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Dispute {

    public static final String STATUS_PENDING = "待处理";
    public static final String STATUS_HANDLED = "已处理";
    public static final String RULING_BUYER = "支持买家";
    public static final String RULING_SELLER = "驳回买家";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "goods_id")
    private Long goodsId;

    private String goodsTitle;

    /** 发起人（买家）与被申请方（卖家） */
    @Column(name = "initiator_id")
    private Long initiatorId;

    @Column(name = "respondent_id")
    private Long respondentId;

    /** 申请理由：货不对板 / 未收到货 / 卖家拒绝退款 / 其他 */
    private String reason;

    /** 补充说明 */
    @Column(length = 500)
    private String detail;

    private String status;

    private String ruling;

    @Column(name = "ruling_note", length = 500)
    private String rulingNote;

    @Column(name = "handler_id")
    private Long handlerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = STATUS_PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
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

    public Long getRespondentId() {
        return respondentId;
    }

    public void setRespondentId(Long respondentId) {
        this.respondentId = respondentId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRuling() {
        return ruling;
    }

    public void setRuling(String ruling) {
        this.ruling = ruling;
    }

    public String getRulingNote() {
        return rulingNote;
    }

    public void setRulingNote(String rulingNote) {
        this.rulingNote = rulingNote;
    }

    public Long getHandlerId() {
        return handlerId;
    }

    public void setHandlerId(Long handlerId) {
        this.handlerId = handlerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }
}
