package com.azneotech.paymentservice.controllers;

import com.azneotech.paymentservice.messaging.PaymentResultMessage;
import com.azneotech.paymentservice.services.PaymentLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StripeWebhookControllerTest {

    private static final String SECRET = "whsec_test";

    private final PaymentLedger ledger = mock(PaymentLedger.class);
    private final StripeWebhookController controller = new StripeWebhookController(new ObjectMapper(), ledger, SECRET);

    private static String event(String type, String paymentStatus, String orderId) {
        return """
                {"id":"evt_1","object":"event","type":"%s","data":{"object":{"id":"cs_1","object":"checkout.session",
                 "payment_status":"%s","metadata":{"orderId":"%s"}}}}""".formatted(type, paymentStatus, orderId);
    }

    private static String sign(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + v1;
    }

    @Test
    void aPaidSessionReportsPaid() throws Exception {
        String payload = event("checkout.session.completed", "paid", "ORD-1");

        assertThat(controller.onEvent(payload, sign(payload, SECRET)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ledger).recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "STRIPE", null, null, null);
    }

    @Test
    void aCompletedSessionStillWaitingForADelayedMethodIsNotPaidYet() throws Exception {
        String unpaid = event("checkout.session.completed", "unpaid", "ORD-1");
        String settled = event("checkout.session.async_payment_succeeded", "paid", "ORD-1");

        controller.onEvent(unpaid, sign(unpaid, SECRET));
        verify(ledger, never()).recordOutcome(any(), any(), any(), any(), any(), any());
        controller.onEvent(settled, sign(settled, SECRET));
        verify(ledger).recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "STRIPE", null, null, null);
    }

    @Test
    void expiredAndFailedSessionsReportFailed() throws Exception {
        String expired = event("checkout.session.expired", "unpaid", "ORD-2");
        String failed = event("checkout.session.async_payment_failed", "unpaid", "ORD-3");

        controller.onEvent(expired, sign(expired, SECRET));
        controller.onEvent(failed, sign(failed, SECRET));

        verify(ledger).recordOutcome("ORD-2", PaymentResultMessage.Status.FAILED, "STRIPE", null, null, "Payment failed or the payment link expired");
        verify(ledger).recordOutcome("ORD-3", PaymentResultMessage.Status.FAILED, "STRIPE", null, null, "Payment failed or the payment link expired");
    }

    @Test
    void aWrongMissingOrTamperedSignatureIsRejected() throws Exception {
        String payload = event("checkout.session.completed", "paid", "ORD-1");
        String other = event("checkout.session.expired", "unpaid", "ORD-1");

        assertThat(controller.onEvent(payload, sign(payload, "someone-elses-secret")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.onEvent(payload, sign(other, SECRET)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.onEvent(payload, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(ledger, never()).recordOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void withoutAConfiguredSecretEverythingIsRejected() throws Exception {
        StripeWebhookController unconfigured = new StripeWebhookController(new ObjectMapper(), ledger, "");
        String payload = event("checkout.session.completed", "paid", "ORD-1");

        assertThat(unconfigured.onEvent(payload, sign(payload, SECRET)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(ledger, never()).recordOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void eventsWithoutAnOrderOrOfOtherKindsAreAcknowledgedAndIgnored() throws Exception {
        String noOrder = event("checkout.session.completed", "paid", "");
        String other = event("charge.succeeded", "paid", "ORD-1");

        assertThat(controller.onEvent(noOrder, sign(noOrder, SECRET)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.onEvent(other, sign(other, SECRET)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ledger, never()).recordOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPaidSessionPassesThePaymentIntentToTheLedger() throws Exception {
        String payload = """
                {"type":"checkout.session.completed","data":{"object":{"id":"cs_1","payment_status":"paid",
                 "payment_intent":"pi_7","metadata":{"orderId":"ORD-1"}}}}""";

        controller.onEvent(payload, sign(payload, SECRET));

        verify(ledger).recordOutcome("ORD-1", PaymentResultMessage.Status.PAID, "STRIPE", "pi_7", null, null);
    }

}
