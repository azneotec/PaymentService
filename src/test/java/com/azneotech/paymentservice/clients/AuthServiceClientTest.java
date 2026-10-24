package com.azneotech.paymentservice.clients;

import com.azneotech.paymentservice.dtos.ValidateTokenRequestDto;
import com.azneotech.paymentservice.dtos.ValidateTokenResponseDto;
import com.azneotech.paymentservice.exceptions.DownstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceClientTest {

    private static final String URL = "http://user-auth-service/auth/validateToken";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthServiceClient client = new AuthServiceClient(restTemplate);

    @Test
    void asksUserAuthServiceByItsEurekaName() {
        ValidateTokenResponseDto answer = new ValidateTokenResponseDto(true, 5L, "alice@example.com", List.of("ROLE_USER"));
        when(restTemplate.postForObject(eq(URL), eq(new ValidateTokenRequestDto("abc")), eq(ValidateTokenResponseDto.class))).thenReturn(answer);

        assertThat(client.validateToken("abc")).isEqualTo(answer);
    }

    @Test
    void anUnreachableOrEmptyAnswerIsADownstreamFailure() {
        when(restTemplate.postForObject(eq(URL), any(), eq(ValidateTokenResponseDto.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new IllegalStateException("No instances available for user-auth-service"))
                .thenReturn(null);

        assertThatThrownBy(() -> client.validateToken("abc")).isInstanceOf(DownstreamUnavailableException.class);
        assertThatThrownBy(() -> client.validateToken("abc")).isInstanceOf(DownstreamUnavailableException.class);
        assertThatThrownBy(() -> client.validateToken("abc")).isInstanceOf(DownstreamUnavailableException.class);
    }

}
