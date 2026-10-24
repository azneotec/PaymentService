package com.azneotech.paymentservice.messaging;

import com.azneotech.paymentservice.models.Payment;
import com.azneotech.paymentservice.services.ReceiptService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;

/**
 * Emails the customer their receipt. NotificationService delivers any {@link EmailMessage} it finds on the
 * {@code order-notification} topic, so the recipient, subject and HTML body are decided here.
 */
@Component
public class ReceiptEmailPublisher {

    /** Same JSON shape as NotificationService's {@code EmailDto}. */
    public record EmailMessage(String to, String from, String subject, String body) {
    }

    private static final DateTimeFormatter PAID_AT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String sender;

    public ReceiptEmailPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${payment.topics.receipt-notification}") String topic,
                                 @Value("${payment.notification.sender}") String sender) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.sender = sender;
    }

    /** Keyed by the order number, like the payment results. Failures surface to the caller. */
    public void send(Payment payment) {
        EmailMessage email = new EmailMessage(payment.getCustomerEmail(), sender,
                "Payment receipt " + payment.getReceiptNumber() + " for order " + payment.getOrderNumber(), body(payment));
        try {
            kafkaTemplate.send(topic, payment.getOrderNumber(), objectMapper.writeValueAsString(email)).get();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the receipt email for " + payment.getOrderNumber(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing the receipt email for " + payment.getOrderNumber(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not publish the receipt email for " + payment.getOrderNumber(), e);
        }
    }

    private static String body(Payment payment) {
        String method = payment.getPaymentMethod() == null ? "" : "<tr><td>Payment method</td><td>%s</td></tr>"
                .formatted(HtmlUtils.htmlEscape(payment.getPaymentMethod()));
        return """
                <p>Hi %s,</p>
                <p>Thank you, we have received your payment. This is your receipt.</p>
                <table cellpadding="4">
                <tr><td>Receipt number</td><td>%s</td></tr>
                <tr><td>Order number</td><td>%s</td></tr>
                <tr><td>Amount paid</td><td>%s %s</td></tr>
                %s<tr><td>Paid on</td><td>%s</td></tr>
                <tr><td>Payment reference</td><td>%s</td></tr>
                </table>""".formatted(
                HtmlUtils.htmlEscape(payment.getCustomerName() == null ? "there" : payment.getCustomerName()),
                HtmlUtils.htmlEscape(payment.getReceiptNumber()),
                HtmlUtils.htmlEscape(payment.getOrderNumber()),
                HtmlUtils.htmlEscape(payment.getCurrency()),
                ReceiptService.formatAmount(payment.getAmountMinor(), payment.getCurrency()),
                method,
                PAID_AT.format(payment.getPaidAt()),
                HtmlUtils.htmlEscape(payment.getGatewayPaymentId() == null ? "-" : payment.getGatewayPaymentId()));
    }

}
