package com.azneotech.paymentservice.dtos;

import java.time.Instant;

/** The JSON body of an authentication failure; same fields as OrderManagementService's error body. */
public record ErrorResponseDto(int status, String error, String message, Instant timestamp) {
}
