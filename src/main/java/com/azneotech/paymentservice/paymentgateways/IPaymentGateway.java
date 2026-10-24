package com.azneotech.paymentservice.paymentgateways;

public interface IPaymentGateway {

    /** Short name stored in the transaction log, e.g. {@code RAZORPAY}. */
    String name();

    /** ISO 4217 code (upper case) of the currency this gateway charges in. */
    String currency();

    String getPaymentLink(
            Long amount,
            String phoneNumber,
            String name,
            String email,
            String orderId
    );
}
