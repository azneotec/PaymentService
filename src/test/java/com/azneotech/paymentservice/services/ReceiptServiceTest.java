package com.azneotech.paymentservice.services;

import com.azneotech.paymentservice.dtos.ReceiptDto;
import com.azneotech.paymentservice.exceptions.PaymentNotFoundException;
import com.azneotech.paymentservice.exceptions.ReceiptNotAvailableException;
import com.azneotech.paymentservice.models.Payment;
import com.azneotech.paymentservice.models.PaymentStatus;
import com.azneotech.paymentservice.repos.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptServiceTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ReceiptService service = new ReceiptService(payments);

    private Payment payment(PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrderNumber("ORD-1");
        payment.setGateway("RAZORPAY");
        payment.setAmountMinor(24220L);
        payment.setCurrency("INR");
        payment.setStatus(status);
        payment.setCustomerName("Alice");
        payment.setCustomerEmail("alice@example.com");
        payment.setGatewayPaymentId("pay_9");
        payment.setPaymentMethod("upi");
        payment.setReceiptNumber("RCT-ORD-1");
        payment.setPaidAt(Instant.parse("2026-10-24T10:00:00Z"));
        when(payments.findByOrderNumber("ORD-1")).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void aPaidOrderHasAReceiptWithAMaskedEmail() {
        payment(PaymentStatus.PAID);

        ReceiptDto receipt = service.getReceipt("ORD-1", "alice@example.com", false);

        assertThat(receipt).isEqualTo(new ReceiptDto("RCT-ORD-1", "ORD-1", "RAZORPAY", "upi", "pay_9", "242.20", 24220L,
                "INR", "Alice", "a****@example.com", Instant.parse("2026-10-24T10:00:00Z")));
    }

    @Test
    void theOwnerIsRecognisedByEmailIgnoringCase() {
        payment(PaymentStatus.PAID);

        assertThat(service.getReceipt("ORD-1", "ALICE@Example.com", false).orderNumber()).isEqualTo("ORD-1");
    }

    @Test
    void someoneElsesReceiptLooksLikeAnUnknownOrder() {
        payment(PaymentStatus.PAID);

        assertThatThrownBy(() -> service.getReceipt("ORD-1", "mallory@example.com", false)).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.getReceipt("ORD-1", null, false)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void someoneElsesUnpaidOrderIsNotRevealedEither() {
        payment(PaymentStatus.LINK_CREATED);

        assertThatThrownBy(() -> service.getReceipt("ORD-1", "mallory@example.com", false)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void anAdminReadsAnyReceipt() {
        payment(PaymentStatus.PAID);

        assertThat(service.getReceipt("ORD-1", "admin@example.com", true).orderNumber()).isEqualTo("ORD-1");
        assertThat(service.getReceipt("ORD-1", null, true).orderNumber()).isEqualTo("ORD-1");
    }

    @Test
    void anUnpaidOrderHasNoReceipt() {
        payment(PaymentStatus.LINK_CREATED);

        assertThatThrownBy(() -> service.getReceipt("ORD-1", "alice@example.com", false)).isInstanceOf(ReceiptNotAvailableException.class);
    }

    @Test
    void anUnknownOrderIsNotFound() {
        when(payments.findByOrderNumber("ORD-2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReceipt("ORD-2", "alice@example.com", false)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void formatsAmountsByTheCurrencysMinorUnits() {
        assertThat(ReceiptService.formatAmount(24220, "INR")).isEqualTo("242.20");
        assertThat(ReceiptService.formatAmount(5, "USD")).isEqualTo("0.05");
        assertThat(ReceiptService.formatAmount(500, "JPY")).isEqualTo("500");
    }

}
