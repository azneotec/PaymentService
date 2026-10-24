package com.azneotech.paymentservice.services;

import com.azneotech.paymentservice.exceptions.PaymentAlreadyPaidException;
import com.azneotech.paymentservice.messaging.PaymentResultMessage;
import com.azneotech.paymentservice.messaging.PaymentResultPublisher;
import com.azneotech.paymentservice.messaging.ReceiptEmailPublisher;
import com.azneotech.paymentservice.models.Payment;
import com.azneotech.paymentservice.models.PaymentEvent;
import com.azneotech.paymentservice.models.PaymentStatus;
import com.azneotech.paymentservice.repos.PaymentEventRepository;
import com.azneotech.paymentservice.repos.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The transaction log. Records every payment link and every outcome a gateway reports, and tells
 * OrderManagementService about an outcome on {@code payment-result} in the same transaction, so an outcome is
 * either logged and published, or neither (and the gateway's webhook retry tries again). A repeat of an outcome
 * the payment already reflects is logged but not published twice, and a paid payment never changes again.
 */
@Service
public class PaymentLedger {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedger.class);

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentResultPublisher resultPublisher;
    private final ReceiptEmailPublisher receiptEmailPublisher;
    private final Clock clock;

    public PaymentLedger(PaymentRepository payments, PaymentEventRepository events, PaymentResultPublisher resultPublisher,
                         ReceiptEmailPublisher receiptEmailPublisher, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.resultPublisher = resultPublisher;
        this.receiptEmailPublisher = receiptEmailPublisher;
        this.clock = clock;
    }

    /** Called before a link is created: an order that is already paid must not be asked to pay again. */
    @Transactional(readOnly = true)
    public void assertNotPaid(String orderNumber) {
        if (payments.findByOrderNumber(orderNumber).filter(p -> p.getStatus() == PaymentStatus.PAID).isPresent()) {
            throw new PaymentAlreadyPaidException(orderNumber);
        }
    }

    /** A new link replaces the previous one of the order (a retried request, or a link that expired). */
    @Transactional
    public void recordLinkCreated(String orderNumber, Long amountMinor, String currency, String gateway,
                                  String customerName, String customerEmail, String customerPhone, String paymentLink) {
        Payment payment = payments.findByOrderNumber(orderNumber).orElseGet(Payment::new);
        if (payment.getStatus() == PaymentStatus.PAID) {
            throw new PaymentAlreadyPaidException(orderNumber);
        }
        payment.setOrderNumber(orderNumber);
        payment.setGateway(gateway);
        payment.setAmountMinor(amountMinor);
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.LINK_CREATED);
        payment.setPaymentLink(paymentLink);
        payment.setCustomerName(customerName);
        payment.setCustomerEmail(customerEmail);
        payment.setCustomerPhone(customerPhone);
        payment = payments.save(payment);
        events.save(new PaymentEvent(payment.getId(), PaymentStatus.LINK_CREATED, PaymentEvent.SOURCE_REQUEST, null, null, clock.instant()));
    }

    /**
     * An outcome reported by a gateway webhook. {@code gatewayPaymentId} and {@code paymentMethod} are null when the
     * event does not carry them. An order this service has no record of is still reported, as before the log existed.
     */
    @Transactional
    public void recordOutcome(String orderNumber, PaymentResultMessage.Status status, String source,
                              String gatewayPaymentId, String paymentMethod, String message) {
        Optional<Payment> found = payments.findByOrderNumber(orderNumber);
        if (found.isEmpty()) {
            log.warn("Payment {} for order {} has no record in the transaction log; reporting it anyway", status, orderNumber);
            resultPublisher.publish(new PaymentResultMessage(orderNumber, status, null, message));
            return;
        }

        Payment payment = found.get();
        PaymentStatus outcome = PaymentStatus.valueOf(status.name());
        Instant now = clock.instant();
        events.save(new PaymentEvent(payment.getId(), outcome, source, gatewayPaymentId, message, now));
        if (payment.getStatus() == PaymentStatus.PAID || payment.getStatus() == outcome) {
            log.info("Ignoring {} for order {}: the payment is already {}", outcome, orderNumber, payment.getStatus());
            return;
        }

        payment.setStatus(outcome);
        if (gatewayPaymentId != null) {
            payment.setGatewayPaymentId(gatewayPaymentId);
        }
        if (paymentMethod != null) {
            payment.setPaymentMethod(paymentMethod);
        }
        if (outcome == PaymentStatus.PAID) {
            payment.setPaidAt(now);
            payment.setReceiptNumber(ReceiptService.RECEIPT_PREFIX + orderNumber);
        }
        payments.save(payment);
        resultPublisher.publish(new PaymentResultMessage(orderNumber, status, null, message));
        if (outcome == PaymentStatus.PAID) {
            afterCommit(() -> sendReceipt(payment));
        }
    }

    /** The receipt is secondary: if the email cannot be queued the payment is still confirmed, and GET .../receipt works. */
    private void sendReceipt(Payment payment) {
        if (payment.getCustomerEmail() == null || payment.getCustomerEmail().isBlank()) {
            log.warn("Order {} has no customer email, so its receipt was not emailed", payment.getOrderNumber());
            return;
        }
        try {
            receiptEmailPublisher.send(payment);
        } catch (RuntimeException e) {
            log.error("Could not email the receipt of order {}", payment.getOrderNumber(), e);
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

}
