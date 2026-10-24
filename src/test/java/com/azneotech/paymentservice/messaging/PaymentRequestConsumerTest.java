package com.azneotech.paymentservice.messaging;

import com.azneotech.paymentservice.exceptions.PaymentAlreadyPaidException;
import com.azneotech.paymentservice.services.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentRequestConsumerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentResultPublisher publisher = mock(PaymentResultPublisher.class);
    private final PaymentRequestConsumer consumer = new PaymentRequestConsumer(new ObjectMapper(), paymentService, publisher);

    private static final String REQUEST = """
            {"orderNumber":"ORD-1","amountMinor":24220,"currency":"INR","customerName":"Alice",
             "email":"alice@example.com","phoneNumber":"+15551234567","extra":"ignored"}""";

    @Test
    void createsTheLinkAndReportsItKeyedByTheOrder() {
        when(paymentService.getPaymentLink(24220L, "+15551234567", "Alice", "alice@example.com", "ORD-1"))
                .thenReturn("https://rzp.io/x");

        consumer.onPaymentRequested(REQUEST);

        verify(publisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.LINK_CREATED, "https://rzp.io/x", null));
    }

    @Test
    void reportsFailureWhenTheGatewayRefuses() {
        when(paymentService.getPaymentLink(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Authentication failed"));

        consumer.onPaymentRequested(REQUEST);

        verify(publisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.FAILED, null, "Could not create a payment link"));
    }

    @Test
    void reportsPaidAgainWhenTheOrderIsAlreadyPaid() {
        when(paymentService.getPaymentLink(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentAlreadyPaidException("ORD-1"));

        consumer.onPaymentRequested(REQUEST);

        verify(publisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.PAID, null, null));
    }

    @Test
    void leavesTheRequestForRedeliveryWhenTheTransactionLogIsDown() {
        when(paymentService.getPaymentLink(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> consumer.onPaymentRequested(REQUEST)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void dropsMessagesItCannotUse() {
        consumer.onPaymentRequested("not json");
        consumer.onPaymentRequested("{\"amountMinor\":100}");
        consumer.onPaymentRequested("{\"orderNumber\":\"ORD-1\"}");
        consumer.onPaymentRequested("{\"orderNumber\":\"ORD-1\",\"amountMinor\":0}");

        verifyNoInteractions(paymentService, publisher);
    }

    @Test
    void listensToThePaymentRequestedTopicProperty() throws NoSuchMethodException {
        KafkaListener listener = PaymentRequestConsumer.class.getMethod("onPaymentRequested", String.class)
                .getAnnotation(KafkaListener.class);

        assertThat(listener.topics()).containsExactly("${payment.topics.payment-requested}");
        verify(publisher, org.mockito.Mockito.never()).publish(any());
    }

}
