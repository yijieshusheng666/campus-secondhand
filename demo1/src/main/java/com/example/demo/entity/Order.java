package com.example.demo.entity;

import com.example.demo.service.OrderStateMachine;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_buyer", columnList = "buyer_id"),
        @Index(name = "idx_orders_seller", columnList = "seller_id"),
        @Index(name = "idx_orders_status", columnList = "status")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goods_id")
    private Long goodsId;
    private String goodsTitle;
    private BigDecimal goodsPrice;
    private String goodsImage;

    @Column(name = "buyer_id")
    private Long buyerId;
    private String buyerName;

    @Column(name = "seller_id")
    private Long sellerId;

    private String receiver;
    private String phone;
    private String address;
    private String status;

    // B2 担保交易：资金状态（未付款 / 平台托管 / 已放款 / 已退回）与关键时间点
    @Column(name = "escrow_status")
    private String escrowStatus;
    private LocalDateTime paidAt;
    private LocalDateTime settledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            // 担保交易：下单先进入待付款，付款后货款才托管到平台
            status = OrderStateMachine.PENDING_PAY;
        }
        if (escrowStatus == null) {
            escrowStatus = OrderStateMachine.escrowOf(status);
        }
    }

    /** 老数据没有资金状态字段，按订单状态推导，保证展示一致。 */
    public String getEscrowStatus() {
        return escrowStatus != null ? escrowStatus : OrderStateMachine.escrowOf(status);
    }

    public void setEscrowStatus(String escrowStatus) {
        this.escrowStatus = escrowStatus;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }

    /**
     * 当前状态允许的下一步操作（由状态机给出），前端据此渲染按钮。
     * 放在实体上是为了把「状态规则」留在后端一处，避免前端再抄一遍。
     */
    @Transient
    public List<Map<String, String>> getNextActions() {
        return OrderStateMachine.nextActions(status);
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

    public BigDecimal getGoodsPrice() {
        return goodsPrice;
    }

    public void setGoodsPrice(BigDecimal goodsPrice) {
        this.goodsPrice = goodsPrice;
    }

    public String getGoodsImage() {
        return goodsImage;
    }

    public void setGoodsImage(String goodsImage) {
        this.goodsImage = goodsImage;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
