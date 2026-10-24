package com.azneotech.paymentservice.models;

/** Where a payment stands. {@link #PAID} is final: nothing the gateway sends afterwards changes it. */
public enum PaymentStatus {
    /** The link exists and the customer has not paid yet. */
    LINK_CREATED,
    PAID,
    /** The link expired or was cancelled, or the gateway reported the payment as failed. */
    FAILED
}
