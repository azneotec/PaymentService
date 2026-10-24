package com.azneotech.paymentservice.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Consumed from {@code payment-requested}; OrderManagementService publishes it for an order that needs paying.
 * {@code amountMinor} is in the currency's smallest unit (paise for INR), what {@code POST /payment} calls {@code amount}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequestedMessage(
        String orderNumber,
        Long amountMinor,
        String currency,
        String customerName,
        String email,
        String phoneNumber) {
}
