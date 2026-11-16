package com.example.marketplace.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class Security {
    @Bean
    @Profile("local")
    UserDetailsService localUsers(@Value("${LOCAL_PASSWORD}") String password) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("customer").password(encoder.encode(password)).roles("CUSTOMER").build(),
                User.withUsername("customer2").password(encoder.encode(password)).roles("CUSTOMER").build(),
                User.withUsername("seller")
                        .password(encoder.encode(password))
                        .roles("SELLER", "CUSTOMER")
                        .build(),
                User.withUsername("seller2")
                        .password(encoder.encode(password))
                        .roles("SELLER", "CUSTOMER")
                        .build(),
                User.withUsername("admin")
                        .password(encoder.encode(password))
                        .roles("ADMIN", "CUSTOMER")
                        .build());
    }

    @Bean
    @Profile("local")
    SecurityFilterChain local(HttpSecurity http) throws Exception {
        base(http);
        http.formLogin(f -> f.defaultSuccessUrl("/", true));
        return http.build();
    }

    @Bean
    @Profile("!local")
    org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.client.provider.marketplace.issuer-uri}") String issuer,
            @Value("${marketplace.identity.audience}") String audience) {
        var decoder =
                org.springframework.security.oauth2.jwt.JwtDecoders
                        .<org.springframework.security.oauth2.jwt.NimbusJwtDecoder>fromIssuerLocation(issuer);
        var audienceValidator =
                new org.springframework.security.oauth2.core.OAuth2TokenValidator<
                        org.springframework.security.oauth2.jwt.Jwt>() {
                    public org.springframework.security.oauth2.core.OAuth2TokenValidatorResult validate(
                            org.springframework.security.oauth2.jwt.Jwt token) {
                        if (token.getAudience().contains(audience))
                            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
                        return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                                new org.springframework.security.oauth2.core.OAuth2Error(
                                        "invalid_token", "Wrong audience", null));
                    }
                };
        decoder.setJwtValidator(
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(issuer),
                        audienceValidator));
        return decoder;
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    @Profile("!local")
    SecurityFilterChain bearer(
            HttpSecurity http, @Value("${marketplace.identity.roles-client}") String rolesClient)
            throws Exception {
        http.securityMatcher(
                r -> {
                    String value = r.getHeader("Authorization");
                    return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7);
                });
        base(http);
        http.csrf(c -> c.disable()); // This chain accepts signed bearer tokens only; never session
        // authentication.
        http.sessionManagement(
                s ->
                        s.sessionCreationPolicy(
                                org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        http.securityContext(
                c ->
                        c.securityContextRepository(
                                new org.springframework.security.web.context.NullSecurityContextRepository()));
        http.requestCache(c -> c.disable());
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> IdentityClaims.authorities(jwt.getClaims(), rolesClient));
        http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    @Profile("!local")
    SecurityFilterChain oidc(
            HttpSecurity http, @Value("${marketplace.identity.roles-client}") String rolesClient)
            throws Exception {
        base(http);
        var delegate = new org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService();
        http.oauth2Login(
                login ->
                        login
                                .loginPage("/oauth2/authorization/marketplace")
                                .userInfoEndpoint(
                                        info ->
                                                info.oidcUserService(
                                                        request -> {
                                                            var user = delegate.loadUser(request);
                                                            var authorities =
                                                                    new java.util.HashSet<
                                                                            org.springframework.security.core.GrantedAuthority>(
                                                                            user.getAuthorities());
                                                            // Application roles come from the issuer-signed ID token, not browser
                                                            // input or arbitrary userinfo.
                                                            authorities.addAll(
                                                                    IdentityClaims.authorities(
                                                                            user.getIdToken().getClaims(), rolesClient));
                                                            return new org.springframework.security.oauth2.core.oidc.user
                                                                    .DefaultOidcUser(
                                                                    authorities, user.getIdToken(), user.getUserInfo(), "sub");
                                                        }))
                                .defaultSuccessUrl("/", true));
        http.exceptionHandling(
                e ->
                        e.defaultAuthenticationEntryPointFor(
                                (request, response, error) -> response.sendError(401),
                                r -> r.getRequestURI().startsWith("/api/")));
        http.logout(l -> l.logoutSuccessUrl("/"));
        return http.build();
    }

    private void base(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                a ->
                        a.requestMatchers(
                                        org.springframework.http.HttpMethod.GET,
                                        "/",
                                        "/login",
                                        "/oauth2/**",
                                        "/login/oauth2/**",
                                        "/*.js",
                                        "/*.css",
                                        "/api/v1/csrf",
                                        "/api/v1/products/**",
                                        "/api/v1/search/**",
                                        "/api/v1/categories",
                                        "/api/v1/offers/*/availability",
                                        "/actuator/health/**")
                                .permitAll()
                                .requestMatchers("/api/v1/payments/webhooks/**")
                                .permitAll()
                                .requestMatchers("/api/v1/admin/**")
                                .hasRole("ADMIN")
                                .requestMatchers("/actuator/**")
                                .hasRole("ADMIN")
                                .anyRequest()
                                .authenticated());
        http.csrf(c -> c.ignoringRequestMatchers("/api/v1/payments/webhooks/**"));
        http.headers(
                h ->
                        h.contentSecurityPolicy(
                                c ->
                                        c.policyDirectives(
                                                "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors"
                                                        + " 'none'; base-uri 'self'")));
    }
}
