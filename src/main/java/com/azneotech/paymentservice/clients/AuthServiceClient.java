package com.azneotech.paymentservice.clients;

import com.azneotech.paymentservice.dtos.ValidateTokenRequestDto;
import com.azneotech.paymentservice.dtos.ValidateTokenResponseDto;
import com.azneotech.paymentservice.exceptions.DownstreamUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Calls UserAuthService by its Eureka name through the {@code @LoadBalanced} RestTemplate. */
@Service
public class AuthServiceClient implements IAuthClient {

    private static final String SERVICE = "user-auth-service";
    private static final String VALIDATE_TOKEN_URL = "http://" + SERVICE + "/auth/validateToken";

    private final RestTemplate restTemplate;

    public AuthServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ValidateTokenResponseDto validateToken(String token) {
        try {
            ValidateTokenResponseDto response = restTemplate.postForObject(
                    VALIDATE_TOKEN_URL, new ValidateTokenRequestDto(token), ValidateTokenResponseDto.class);
            if (response == null) {
                throw new DownstreamUnavailableException(SERVICE);
            }
            return response;
        } catch (RestClientException | IllegalStateException e) {
            // IllegalStateException is what the load balancer throws when no instance is registered.
            throw new DownstreamUnavailableException(SERVICE, e);
        }
    }

}
