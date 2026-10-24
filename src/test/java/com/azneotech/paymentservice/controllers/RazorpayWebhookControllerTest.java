package com.azneotech.paymentservice.controllers;

import com.azneotech.paymentservice.messaging.PaymentResultMessage;
import com.azneotech.paymentservice.messaging.PaymentResultPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RazorpayWebhookControllerTest {

    private static final String SECRET = "whsec_test";

    private final PaymentResultPublisher publisher = mock(PaymentResultPublisher.class);
    private final RazorpayWebhookController controller = new RazorpayWebhookController(new ObjectMapper(), publisher, SECRET);

    private static String event(String name, String referenceId) {
        return """
                {"event":"%s","payload":{"payment_link":{"entity":{"reference_id":"%s"}}}}""".formatted(name, referenceId);
    }

    private static String sign(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void paidLinkReportsPaid() throws Exception {
        String payload = event("payment_link.paid", "ORD-1");

        var response = controller.onEvent(payload, sign(payload, SECRET));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publisher).publish(new PaymentResultMessage("ORD-1", PaymentResultMessage.Status.PAID, null, null));
    }

    @Test
    void expiredAndCancelledLinksReportFailed() throws Exception {
        String expired = event("payment_link.expired", "ORD-2");
        String cancelled = event("payment_link.cancelled", "ORD-3");

        controller.onEvent(expired, sign(expired, SECRET));
        controller.onEvent(cancelled, sign(cancelled, SECRET));

        verify(publisher).publish(new PaymentResultMessage("ORD-2", PaymentResultMessage.Status.FAILED, null, "Payment link expired"));
        verify(publisher).publish(new PaymentResultMessage("ORD-3", PaymentResultMessage.Status.FAILED, null, "Payment link cancelled"));
    }

    @Test
    void aWrongOrMissingSignatureIsRejected() throws Exception {
        String payload = event("payment_link.paid", "ORD-1");

        assertThat(controller.onEvent(payload, sign(payload, "someone-elses-secret")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.onEvent(payload, "deadbeef").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.onEvent(payload, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(publisher, never()).publish(any());
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String signed = event("payment_link.expired", "ORD-1");
        String tampered = event("payment_link.paid", "ORD-1");

        assertThat(controller.onEvent(tampered, sign(signed, SECRET)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(publisher, never()).publish(any());
    }

    @Test
    void withoutAConfiguredSecretEverythingIsRejected() throws Exception {
        RazorpayWebhookController unconfigured = new RazorpayWebhookController(new ObjectMapper(), publisher, "");
        String payload = event("payment_link.paid", "ORD-1");

        assertThat(unconfigured.onEvent(payload, sign(payload, SECRET)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(publisher, never()).publish(any());
    }

    @Test
    void otherEventsAreAcknowledgedAndIgnored() throws Exception {
        String payload = event("payment.captured", "ORD-1");

        assertThat(controller.onEvent(payload, sign(payload, SECRET)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publisher, never()).publish(any());
    }

}
