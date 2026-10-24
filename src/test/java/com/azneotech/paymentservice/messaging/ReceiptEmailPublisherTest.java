package com.azneotech.paymentservice.messaging;

import com.azneotech.paymentservice.models.Payment;
import com.azneotech.paymentservice.models.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptEmailPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReceiptEmailPublisher publisher = new ReceiptEmailPublisher(kafka, objectMapper, "order-notification", "shop@example.com");

    private static Payment paidPayment(String name) {
        Payment payment = new Payment();
        payment.setOrderNumber("ORD-1");
        payment.setStatus(PaymentStatus.PAID);
        payment.setAmountMinor(24220L);
        payment.setCurrency("INR");
        payment.setCustomerName(name);
        payment.setCustomerEmail("alice@example.com");
        payment.setPaymentMethod("upi");
        payment.setGatewayPaymentId("pay_9");
        payment.setReceiptNumber("RCT-ORD-1");
        payment.setPaidAt(Instant.parse("2026-10-24T10:00:00Z"));
        return payment;
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesAnEmailNotificationServiceCanSend() throws Exception {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.send(paidPayment("Alice"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("order-notification"), org.mockito.ArgumentMatchers.eq("ORD-1"), json.capture());
        JsonNode email = objectMapper.readTree(json.getValue());
        assertThat(email.get("to").asText()).isEqualTo("alice@example.com");
        assertThat(email.get("from").asText()).isEqualTo("shop@example.com");
        assertThat(email.get("subject").asText()).isEqualTo("Payment receipt RCT-ORD-1 for order ORD-1");
        assertThat(email.get("body").asText()).contains("RCT-ORD-1", "ORD-1", "INR 242.20", "upi", "pay_9", "24 Oct 2026, 10:00 UTC");
    }

    @Test
    @SuppressWarnings("unchecked")
    void escapesCustomerSuppliedText() throws Exception {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.send(paidPayment("<script>alert(1)</script>"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), json.capture());
        assertThat(objectMapper.readTree(json.getValue()).get("body").asText())
                .contains("&lt;script&gt;").doesNotContain("<script>");
    }

    @Test
    void aBrokerFailureSurfaces() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        assertThatThrownBy(() -> publisher.send(paidPayment("Alice"))).isInstanceOf(IllegalStateException.class);
    }

}
