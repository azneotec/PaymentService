package com.azneotech.paymentservice.messaging;

/**
 * Published on {@code payment-result}, keyed by order number. {@code paymentLink} accompanies
 * {@link Status#LINK_CREATED}; {@code message} explains a {@link Status#FAILED}.
 */
public record PaymentResultMessage(String orderNumber, Status status, String paymentLink, String message) {

    public enum Status {
        /** The customer can now pay at {@code paymentLink}. */
        LINK_CREATED,
        PAID,
        FAILED
    }

}
