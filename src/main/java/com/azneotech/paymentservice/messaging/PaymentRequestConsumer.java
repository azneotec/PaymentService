package com.azneotech.paymentservice.messaging;

import com.azneotech.paymentservice.exceptions.PaymentAlreadyPaidException;
import com.azneotech.paymentservice.services.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Creates the payment link for each order OrderManagementService asks to be paid, and answers on
 * {@code payment-result}. A message that cannot be understood is logged and dropped, since redelivering
 * it would never help; one that fails because the database is down is redelivered.
 */
@Component
public class PaymentRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestConsumer.class);

    private final ObjectMapper objectMapper;
    private final PaymentService paymentService;
    private final PaymentResultPublisher resultPublisher;

    public PaymentRequestConsumer(ObjectMapper objectMapper, PaymentService paymentService, PaymentResultPublisher resultPublisher) {
        this.objectMapper = objectMapper;
        this.paymentService = paymentService;
        this.resultPublisher = resultPublisher;
    }

    @KafkaListener(topics = "${payment.topics.payment-requested}")
    public void onPaymentRequested(String message) {
        PaymentRequestedMessage request;
        try {
            request = objectMapper.readValue(message, PaymentRequestedMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Ignoring malformed payment-requested message: {}", message, e);
            return;
        }
        if (request.orderNumber() == null || request.amountMinor() == null || request.amountMinor() <= 0) {
            log.error("Ignoring payment-requested message without an order number or a positive amount: {}", message);
            return;
        }

        String link;
        try {
            link = paymentService.getPaymentLink(request.amountMinor(), request.phoneNumber(), request.customerName(),
                    request.email(), request.orderNumber());
        } catch (PaymentAlreadyPaidException e) {
            // A redelivered request for an order that was paid in the meantime: say so again instead of a second link.
            log.warn("Order {} is already paid; reporting PAID again", request.orderNumber());
            resultPublisher.publish(new PaymentResultMessage(request.orderNumber(), PaymentResultMessage.Status.PAID, null, null));
            return;
        } catch (DataAccessException | TransactionException e) {
            // The transaction log is unavailable, which says nothing about the payment: rethrow so the request is
            // redelivered rather than failing the order.
            throw e;
        } catch (RuntimeException e) {
            // The gateway refused (bad keys, invalid amount, ...). Redelivery would fail the same way, so tell the
            // order its payment failed instead of leaving it waiting for a link that will never come.
            log.error("Could not create a payment link for order {}", request.orderNumber(), e);
            resultPublisher.publish(new PaymentResultMessage(request.orderNumber(),
                    PaymentResultMessage.Status.FAILED, null, "Could not create a payment link"));
            return;
        }
        resultPublisher.publish(new PaymentResultMessage(request.orderNumber(),
                PaymentResultMessage.Status.LINK_CREATED, link, null));
    }

}
