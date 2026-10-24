package com.azneotech.paymentservice.security;

import com.azneotech.paymentservice.clients.IAuthClient;
import com.azneotech.paymentservice.dtos.ValidateTokenResponseDto;
import com.azneotech.paymentservice.exceptions.DownstreamUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final IAuthClient authClient = mock(IAuthClient.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(authClient, new ObjectMapper().registerModule(new JavaTimeModule()));
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aValidTokenAuthenticatesTheCallerWithTheTokensRoles() throws Exception {
        request.addHeader("Authorization", "Bearer abc.def");
        when(authClient.validateToken("abc.def")).thenReturn(new ValidateTokenResponseDto(true, 5L, "alice@example.com", List.of("ROLE_USER")));

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(5L, "alice@example.com"));
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void anInvalidTokenContinuesAnonymously() throws Exception {
        request.addHeader("Authorization", "Bearer nope");
        when(authClient.validateToken("nope")).thenReturn(new ValidateTokenResponseDto(false, null, null, null));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void noBearerHeaderContinuesAnonymouslyWithoutCallingUserAuth() throws Exception {
        request.addHeader("Authorization", "Basic abc");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(authClient);
        verify(chain).doFilter(request, response);
    }

    @Test
    void userAuthBeingDownAnswers503InsteadOfPretendingTheCallerIsAnonymous() throws Exception {
        request.addHeader("Authorization", "Bearer abc");
        when(authClient.validateToken("abc")).thenThrow(new DownstreamUnavailableException("user-auth-service"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Authentication service is unavailable");
        verify(chain, never()).doFilter(request, response);
    }

}
