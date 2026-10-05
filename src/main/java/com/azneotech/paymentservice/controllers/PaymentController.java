package com.azneotech.paymentservice.controllers;

import com.azneotech.paymentservice.dtos.InitiatePaymentRequestDto;
import com.azneotech.paymentservice.services.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @PostMapping
    public String initiatePayment(
            @RequestBody InitiatePaymentRequestDto initiatePaymentRequestDto
    ) {
        // OID#0001 1rupee https://rzp.io/rzp/lWsFo4S
        return paymentService.getPaymentLink(
                initiatePaymentRequestDto.getAmount(),
                initiatePaymentRequestDto.getPhoneNumber(),
                initiatePaymentRequestDto.getName(),
                initiatePaymentRequestDto.getEmail(),
                initiatePaymentRequestDto.getOrderId()
        );
    }
}
