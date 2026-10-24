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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentEventRepository events = mock(PaymentEventRepository.class);
    private final PaymentResultPublisher resultPublisher = mock(PaymentResultPublisher.class);
    private final ReceiptEmailPublisher receiptEmailPublisher = mock(ReceiptEmailPublisher.class);
    private final PaymentLedger ledger = new PaymentLedger(payments, events, resultPublisher, receiptEmailPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void saveReturnsItsArgument() {
        when(payments.save(any(Payment.class))).thenAnswer(call -> {
            Payment payment = call.getArgument(0);
            if (payment.getId() == null) {
                payment.setId(7L);
            }
            return payment;
        });
    }

    private Payment existing(PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(7L);
        payment.setOrderNumber("ORD-1");
        payment.setGateway("RAZORPAY");
        payment.setAmountMinor(24220L);
        payment.setCurrency("INR");
        payment.setStatus(status);
        payment.setCustomerName("Alice");
        payment.setCustomerEmail("alice@example.com");
        when(payments.findByOrderNumber("ORD-1")).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void logsANewLinkAndItsEvent() {
        when(payments.findByOrderNumber("ORD-1")).thenReturn(Optional.empty());

        ledger.recordLinkCreated("ORD-1", 24220L, "INR", "RAZORPAY", "Alice", "alice@example.com", "+1555", "https://rzp.io/x");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(saved.capture());
        assertThat(saved.getValue()).satisfies(p -> {
            assertThat(p.getOrderNumber()).isEqualTo("ORD-1");
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.LINK_CREATED);
            assertThat(p.getAmountMinor()).isEqualTo(24220L);
            assertThat(p.getPaymentLink()).isEqualTo("https://rzp.io/x");
            assertThat(p.getCustomerEmail()).isEqualTo("alice@example.com");
        });
        ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(PaymentStatus.LINK_CREATED);
        assertThat(event.getValue().getSource()).isEqualTo(PaymentEvent.SOURCE_REQUEST);
    }

    @Test
    void aSecondLinkForAFailedOrderReopensItAndKeepsTheRow() {
        Payment payment = existing(PaymentStatus.FAILED);

        ledger.recordLinkCreated("ORD-1", 24220L, "INR", "RAZORPAY", "Alice", "alice@example.com", "+1555", "https://rzp.io/new");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.LINK_CREATED);
        assertThat(payment.getPaymentLink()).isEqualTo("https://rzp.io/new");
        assertThat(payment.getId()).isEqualTo(7L);
    }

    @Test
    void aPaidOrderCannotGetAnotherLink() {
        existing(PaymentStatus.PAID);

        assertThatThrownBy(() -> ledger.assertNotPaid("ORD-1")).isInstanceOf(PaymentAlreadyPaidException.class);
        assertThatThrownBy(() -> ledger.recordLinkCreated("ORD-1", 1L, "INR", "RAZORPAY", "A", "a@b.c", "1", "l"))
                .isInstanceOf(PaymentAlreadyPaidException.class);
    }

    @Test
    void anUnpaidOrUnknownOrderPassesTheCheck() {
        existing(PaymentStatus.LINK_CREATED);
        ledger.assertNotPaid("ORD-1");

        when(payments.findByOrderNumber("ORD-2")).thenReturn(Optional.empty());
        ledger.assertNotPaid("ORD-2");
    }

    @Test
    void paidMarksThePaymentIssuesTheReceiptReportsAndEmails() {
        Payment payment = existing(PaymentStatus.LINK_CREATED);

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "RAZORPAY", "pay_9", "upi", null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getGatewayPaymentId()).isEqualTo("pay_9");
        assertThat(payment.getPaymentMethod()).isEqualTo("upi");
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(payment.getReceiptNumber()).isEqualTo("RCT-ORD-1");
        verify(events).save(any(PaymentEvent.class));
        var order = inOrder(payments, resultPublisher, receiptEmailPublisher);
        order.verify(payments).save(payment);
        order.verify(resultPublisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.PAID, null, null));
        order.verify(receiptEmailPublisher).send(payment);
    }

    @Test
    void failedMarksThePaymentAndSendsNoReceipt() {
        Payment payment = existing(PaymentStatus.LINK_CREATED);

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.FAILED, "RAZORPAY", null, null, "Payment link expired");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getReceiptNumber()).isNull();
        verify(resultPublisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.FAILED, null, "Payment link expired"));
        verify(receiptEmailPublisher, never()).send(any());
    }

    @Test
    void aRepeatedPaidIsLoggedButNotPublishedAgain() {
        existing(PaymentStatus.PAID);

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "RAZORPAY", "pay_9", "upi", null);

        verify(events).save(any(PaymentEvent.class));
        verify(resultPublisher, never()).publish(any());
        verify(receiptEmailPublisher, never()).send(any());
    }

    @Test
    void aPaidPaymentIsNeverMovedToFailed() {
        Payment payment = existing(PaymentStatus.PAID);

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.FAILED, "STRIPE", null, null, "expired");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(resultPublisher, never()).publish(any());
    }

    @Test
    void aFailedPaymentCanStillBecomePaid() {
        Payment payment = existing(PaymentStatus.FAILED);

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "STRIPE", "pi_1", null, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(resultPublisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.PAID, null, null));
    }

    @Test
    void anOrderWithoutARecordIsStillReported() {
        when(payments.findByOrderNumber("ORD-9")).thenReturn(Optional.empty());

        ledger.recordOutcome("ORD-9", PaymentResultMessage.Status.PAID, "RAZORPAY", "pay_1", "card", null);

        verify(resultPublisher).publish(new PaymentResultMessage("ORD-9", PaymentResultMessage.Status.PAID, null, null));
        verify(events, never()).save(any());
        verify(receiptEmailPublisher, never()).send(any());
    }

    @Test
    void aPublishFailureSurfacesSoTheWebhookIsRetried() {
        existing(PaymentStatus.LINK_CREATED);
        doThrow(new IllegalStateException("kafka down")).when(resultPublisher).publish(any());

        assertThatThrownBy(() -> ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "RAZORPAY", null, null, null))
                .isInstanceOf(IllegalStateException.class);
        verify(receiptEmailPublisher, never()).send(any());
    }

    @Test
    void aFailedReceiptEmailDoesNotUndoTheConfirmation() {
        existing(PaymentStatus.LINK_CREATED);
        doThrow(new IllegalStateException("kafka down")).when(receiptEmailPublisher).send(any());

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "RAZORPAY", null, null, null);

        verify(resultPublisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.PAID, null, null));
    }

    @Test
    void noReceiptEmailIsSentWithoutACustomerEmail() {
        existing(PaymentStatus.LINK_CREATED).setCustomerEmail(" ");

        ledger.recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "RAZORPAY", null, null, null);

        verify(receiptEmailPublisher, never()).send(any());
    }

}
