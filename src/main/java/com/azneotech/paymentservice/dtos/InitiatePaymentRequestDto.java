package com.azneotech.paymentservice.dtos;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class InitiatePaymentRequestDto {
    private Long amount;
    private String phoneNumber;
    private String name;
    private String email;
    private String orderId;
}
