package com.azneotech.paymentservice.clients;

import com.azneotech.paymentservice.dtos.ValidateTokenResponseDto;

public interface IAuthClient {

    /** @throws com.azneotech.paymentservice.exceptions.DownstreamUnavailableException if user-auth-service cannot answer */
    ValidateTokenResponseDto validateToken(String token);

}
