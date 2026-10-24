package com.azneotech.paymentservice.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A receipt was asked for a payment that has not been completed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ReceiptNotAvailableException extends RuntimeException {

    public ReceiptNotAvailableException(String orderNumber) {
        super("Order " + orderNumber + " has not been paid, so it has no receipt");
    }

}
