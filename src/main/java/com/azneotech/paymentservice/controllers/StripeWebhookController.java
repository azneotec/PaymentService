package com.azneotech.paymentservice.controllers;

import com.azneotech.paymentservice.messaging.PaymentResultMessage;
import com.azneotech.paymentservice.messaging.PaymentResultPublisher;
import com.azneotech.paymentservice.paymentgateways.StripePaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
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
 * Receives Stripe's checkout events for payment links and reports the outcome to OrderManagementService on
 * {@code payment-result}. The order is found from the {@code orderId} metadata {@link StripePaymentGateway} puts on
 * the link. Anything without a valid {@code Stripe-Signature} is rejected, because this endpoint decides whether an
 * order is treated as paid.
 */
@RestController
@RequestMapping("/stripeWebhook")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final ObjectMapper objectMapper;
    private final PaymentResultPublisher resultPublisher;
    private final String webhookSecret;

    public StripeWebhookController(ObjectMapper objectMapper,
                                   PaymentResultPublisher resultPublisher,
                                   @Value("${stripe.webhook.secret:}") String webhookSecret) {
        this.objectMapper = objectMapper;
        this.resultPublisher = resultPublisher;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping
    public ResponseEntity<Void> onEvent(@RequestBody String payload,
                                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (webhookSecret.isBlank()) {
            log.error("Rejecting a Stripe webhook: stripe.webhook.secret (STRIPE_WEBHOOK_SECRET) is not set");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (signature == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        JsonNode session = event.path("data").path("object");
        String orderNumber = session.path("metadata").path(StripePaymentGateway.ORDER_ID_METADATA).asText("");
        PaymentResultMessage.Status status = switch (event.path("type").asText()) {
            // payment_status is "unpaid" for a delayed method (bank debit) until async_payment_succeeded arrives.
            case "checkout.session.completed" -> "paid".equals(session.path("payment_status").asText()) ? PaymentResultMessage.Status.PAID : null;
            case "checkout.session.async_payment_succeeded" -> PaymentResultMessage.Status.PAID;
            case "checkout.session.async_payment_failed" -> PaymentResultMessage.Status.FAILED;
            case "checkout.session.expired" -> PaymentResultMessage.Status.FAILED;
            default -> null;
        };
        if (status == null || orderNumber.isBlank()) {
            // Not an event we act on; 200 so Stripe does not keep retrying it.
            return ResponseEntity.ok().build();
        }

        resultPublisher.publish(new PaymentResultMessage(orderNumber, status, null,
                status == PaymentResultMessage.Status.FAILED ? "Payment failed or the payment link expired" : null));
        return ResponseEntity.ok().build();
    }

}
