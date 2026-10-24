package com.azneotech.paymentservice.security;

import com.azneotech.paymentservice.clients.IAuthClient;
import com.azneotech.paymentservice.dtos.ValidateTokenResponseDto;
import com.azneotech.paymentservice.exceptions.DownstreamUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads {@code Authorization: Bearer <jwt>}, asks user-auth-service whether it is valid and, if so, populates the
 * {@code SecurityContext} with an {@link AuthenticatedUser} and the token's roles. A missing or invalid token is not
 * rejected here: the request continues anonymously and the authorization rules answer 401. If user-auth-service
 * cannot be reached the request is answered 503, because treating that as "anonymous" would tell a valid user to
 * log in again.
 * <p>
 * Deliberately not a {@code @Component}: it is instantiated inside the {@code SecurityFilterChain} bean so Boot
 * doesn't also register it as a plain servlet filter and run it twice.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final IAuthClient authClient;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(IAuthClient authClient, ObjectMapper objectMapper) {
        this.authClient = authClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean hasBearer = header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());

        if (hasBearer && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            ValidateTokenResponseDto result;
            try {
                result = authClient.validateToken(token);
            } catch (DownstreamUnavailableException e) {
                log.error("Token validation failed", e);
                JwtAuthenticationEntryPoint.write(response, objectMapper, HttpStatus.SERVICE_UNAVAILABLE,
                        "Authentication service is unavailable, please retry");
                return;
            }
            if (result.valid()) {
                List<GrantedAuthority> authorities = result.roles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .map(GrantedAuthority.class::cast)
                        .toList();
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new AuthenticatedUser(result.userId(), result.email()), null, authorities));
            }
        }

        chain.doFilter(request, response);
    }

}
