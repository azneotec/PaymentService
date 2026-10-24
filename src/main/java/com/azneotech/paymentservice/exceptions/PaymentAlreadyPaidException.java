package com.azneotech.paymentservice.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A payment link was asked for an order that has already been paid. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PaymentAlreadyPaidException extends RuntimeException {

    public PaymentAlreadyPaidException(String orderNumber) {
        super("Order " + orderNumber + " is already paid");
    }

}
