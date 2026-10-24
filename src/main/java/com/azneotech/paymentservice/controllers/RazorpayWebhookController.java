package com.azneotech.paymentservice.controllers;

import com.azneotech.paymentservice.messaging.PaymentResultMessage;
import com.azneotech.paymentservice.services.PaymentLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Razorpay's payment-link webhooks, logs them in the {@link PaymentLedger} and reports the outcome to
 * OrderManagementService on {@code payment-result}. The payment link was created with the order number as its {@code reference_id}, which
 * is how the event finds its order. Anything without a valid signature is rejected, because this endpoint
 * decides whether an order is treated as paid.
 */
@RestController
@RequestMapping("/razorpayWebhook")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final ObjectMapper objectMapper;
    private final PaymentLedger paymentLedger;
    private final String webhookSecret;

    public RazorpayWebhookController(ObjectMapper objectMapper,
                                     PaymentLedger paymentLedger,
                                     @Value("${razorpay.webhook.secret}") String webhookSecret) {
        this.objectMapper = objectMapper;
        this.paymentLedger = paymentLedger;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping
    public ResponseEntity<Void> onEvent(@RequestBody String payload,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (webhookSecret.isBlank()) {
            log.error("Rejecting a Razorpay webhook: razorpay.webhook.secret (RAZORPAY_WEBHOOK_SECRET) is not set");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (signature == null || !isValid(payload, signature)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        PaymentResultMessage.Status status = switch (event.path("event").asText()) {
            case "payment_link.paid" -> PaymentResultMessage.Status.PAID;
            case "payment_link.expired", "payment_link.cancelled" -> PaymentResultMessage.Status.FAILED;
            default -> null;
        };
        String orderNumber = event.path("payload").path("payment_link").path("entity").path("reference_id").asText("");
        if (status == null || orderNumber.isBlank()) {
            // Not an event we act on; 200 so Razorpay does not keep retrying it.
            return ResponseEntity.ok().build();
        }

        JsonNode payment = event.path("payload").path("payment").path("entity");
        paymentLedger.recordOutcome(orderNumber, status, "RAZORPAY", payment.path("id").asText(null), payment.path("method").asText(null),
                status == PaymentResultMessage.Status.FAILED ? "Payment link " + event.path("event").asText().substring("payment_link.".length()) : null);
        return ResponseEntity.ok().build();
    }

    private boolean isValid(String payload, String signature) {
        try {
            return Utils.verifyWebhookSignature(payload, signature, webhookSecret);
        } catch (RazorpayException e) {
            log.warn("Could not verify a Razorpay webhook signature", e);
            return false;
        }
    }

}
