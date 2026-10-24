package com.azneotech.paymentservice.dtos;

import java.time.Instant;

/**
 * What {@code GET /payment/{orderNumber}/receipt} returns. {@code amount} is the amount in major units
 * ("242.20"); {@code amountMinor} is the same in the currency's smallest unit. The email is masked because
 * the endpoint does not authenticate the caller; the full receipt goes to the customer by email.
 */
public record ReceiptDto(
        String receiptNumber,
        String orderNumber,
        String gateway,
        String paymentMethod,
        String gatewayPaymentId,
        String amount,
        Long amountMinor,
        String currency,
        String customerName,
        String customerEmail,
        Instant paidAt) {
}
