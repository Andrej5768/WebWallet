package com.andrewcorp.webwallet.config;

import com.andrewcorp.webwallet.auth.AccessTokenValidator;
import com.andrewcorp.webwallet.auth.TokenService;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.error.ProblemDetails;
import com.andrewcorp.webwallet.idempotency.IdempotencyFilter;
import com.andrewcorp.webwallet.idempotency.IdempotencyKeyRepository;
import com.andrewcorp.webwallet.monitoring.RequestRateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;

    private static final RequestMatcher AUTH_PATHS =
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/**");
    private static final RequestMatcher APP_PATHS =
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/app/**");

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemDetails problemDetails,
            LocaleResolver localeResolver,
            JsonMapper jsonMapper,
            RequestRateLimitFilter rateLimitFilter,
            IdempotencyFilter idempotencyFilter) {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> writeProblem(
                response,
                problemDetails.of(ErrorCode.UNAUTHORIZED, localeResolver.resolveLocale(request)),
                jsonMapper,
                true);
        AccessDeniedHandler forbidden = (request, response, ex) -> writeProblem(
                response,
                problemDetails.of(ErrorCode.FORBIDDEN, localeResolver.resolveLocale(request)),
                jsonMapper,
                false);
        // @formatter:off
        http
                // Stateless bearer-token API without a session, so no CSRF. The only cookie, the refresh token, is
                // SameSite=Strict and limited to the auth endpoints (see RefreshTokenCookie)
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(AUTH_PATHS)
                        .permitAll()
                        // Read by the native apps before sign-in
                        .requestMatchers(APP_PATHS)
                        .permitAll()
                        // Called by Monobank; the URL contains a secret of the connection
                        .requestMatchers("/api/v1/banks/monobank/webhook/**")
                        .permitAll()
                        // Called by Telegram with the secret of the webhook in a header
                        .requestMatchers("/api/v1/telegram/webhook")
                        .permitAll()
                        // The links of the digest emails, signed for their user
                        .requestMatchers("/api/v1/notifications/unsubscribe", "/api/v1/notifications/resubscribe")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                // Before the token is checked, so that a flood of requests costs as little as possible
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                // After the user is known, so that a key belongs to its user
                .addFilterAfter(idempotencyFilter, AuthorizationFilter.class);
        // @formatter:on
        return http.build();
    }

    /** Limits the requests to the API per client address, see {@link RequestRateLimitFilter}. */
    @Bean
    public RequestRateLimitFilter requestRateLimitFilter(
            @Value("${app.rate-limit.requests-per-minute}") int requestsPerMinute,
            ProblemDetails problemDetails,
            LocaleResolver localeResolver,
            JsonMapper jsonMapper) {
        return new RequestRateLimitFilter(
                requestsPerMinute,
                (request, response) -> writeProblem(
                        response,
                        problemDetails.of(ErrorCode.TOO_MANY_REQUESTS, localeResolver.resolveLocale(request)),
                        jsonMapper,
                        false));
    }

    /** The filter runs in the security chain only, not a second time as a servlet filter. */
    @Bean
    public FilterRegistrationBean<RequestRateLimitFilter> requestRateLimitRegistration(RequestRateLimitFilter filter) {
        FilterRegistrationBean<RequestRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Replays the response of a create request sent again with the same key, see {@link IdempotencyFilter}. */
    @Bean
    public IdempotencyFilter idempotencyFilter(
            IdempotencyKeyRepository keys,
            Clock clock,
            ProblemDetails problemDetails,
            LocaleResolver localeResolver,
            JsonMapper jsonMapper) {
        return new IdempotencyFilter(
                keys,
                clock,
                (request, response, code) -> writeProblem(
                        response, problemDetails.of(code, localeResolver.resolveLocale(request)), jsonMapper, false));
    }

    /** The filter runs in the security chain only, not a second time as a servlet filter. */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private static void writeProblem(
            HttpServletResponse response, ProblemDetail problem, JsonMapper jsonMapper, boolean challenge)
            throws IOException {
        if (challenge) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Same shape as the ProblemDetail responses rendered by Spring MVC: custom properties on the top level
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType() != null ? problem.getType().toString() : "about:blank");
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        jsonMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * Ignores the access token on the auth endpoints and the app config. They do not need it, and a client sends its
     * expired token along when it refreshes or logs out; validating it would reject the request with 401 before the
     * refresh token cookie is looked at, so a logout would not end the session. The app reads its config on start,
     * before it knows whether its token is still good.
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return request -> AUTH_PATHS.matches(request) || APP_PATHS.matches(request) ? null : resolver.resolve(request);
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public SecretKey jwtSecretKey(AppProperties properties) {
        byte[] secret = Base64.getDecoder().decode(properties.jwt().secret());
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret must be a Base64-encoded key of at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey, AppProperties properties, AccessTokenValidator accessTokenValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()), accessTokenValidator));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        List<String> origins = properties.cors().allowedOrigins();
        cors.setAllowedOrigins(origins == null ? List.of() : origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
