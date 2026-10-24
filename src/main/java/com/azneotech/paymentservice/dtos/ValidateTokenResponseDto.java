package com.azneotech.paymentservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The part of UserAuthService's {@code POST /auth/validateToken} response this service uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidateTokenResponseDto(boolean valid, Long userId, String email, List<String> roles) {

    public ValidateTokenResponseDto {
        roles = roles == null ? List.of() : roles;
    }

}
