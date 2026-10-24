package com.azneotech.paymentservice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Tells OrderManagementService what happened to an order's payment. */
@Component
public class PaymentResultPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public PaymentResultPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${payment.topics.payment-result}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * Keyed by the order number so all results of one order are consumed in the order they were sent.
     * Failures surface to the caller: the webhook answers non-2xx so Razorpay retries it, and the Kafka
     * listener rethrows so the request is redelivered.
     */
    public void publish(PaymentResultMessage message) {
        try {
            kafkaTemplate.send(topic, message.orderNumber(), objectMapper.writeValueAsString(message)).get();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize payment result for " + message.orderNumber(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing payment result for " + message.orderNumber(), e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Could not publish payment result for " + message.orderNumber(), e);
        }
    }

}
