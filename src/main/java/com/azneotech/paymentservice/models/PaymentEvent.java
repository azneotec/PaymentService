package com.azneotech.paymentservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One thing that was reported about a payment: the link being created, or a gateway webhook. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_event")
public class PaymentEvent {

    /** The link was created by this service, not reported by a gateway. */
    public static final String SOURCE_REQUEST = "REQUEST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long paymentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;
    /** {@link #SOURCE_REQUEST}, or the gateway that sent the webhook (RAZORPAY, STRIPE). */
    @Column(nullable = false, length = 16)
    private String source;
    @Column(length = 64)
    private String gatewayPaymentId;
    private String detail;
    @Column(nullable = false)
    private Instant createdAt;

    public PaymentEvent(Long paymentId, PaymentStatus status, String source, String gatewayPaymentId, String detail, Instant createdAt) {
        this.paymentId = paymentId;
        this.status = status;
        this.source = source;
        this.gatewayPaymentId = gatewayPaymentId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

}
