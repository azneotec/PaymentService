package com.azneotech.paymentservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The transaction log row of one order's payment. {@code orderNumber} is the correlation key shared with
 * OrderManagementService and the gateways; {@code amountMinor} is in the currency's smallest unit. The receipt
 * exists once the payment is {@link PaymentStatus#PAID}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 32)
    private String orderNumber;
    @Column(nullable = false, length = 16)
    private String gateway;
    @Column(nullable = false)
    private Long amountMinor;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;
    @Column(length = 512)
    private String paymentLink;
    private String customerName;
    private String customerEmail;
    @Column(length = 32)
    private String customerPhone;
    /** The gateway's id for the payment itself (Razorpay {@code pay_...}, Stripe payment intent), once known. */
    @Column(length = 64)
    private String gatewayPaymentId;
    /** How the customer paid (card, upi, netbanking, ...) when the gateway says so. */
    @Column(length = 32)
    private String paymentMethod;
    @Column(unique = true, length = 40)
    private String receiptNumber;
    private Instant paidAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

}
