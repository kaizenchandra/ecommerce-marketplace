package com.example.marketplace;

import com.example.marketplace.payment.LocalPaymentProvider;
import com.example.marketplace.payment.PaymentProvider;
import com.example.marketplace.shared.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@ActiveProfiles("production")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "COOKIE_SECURE=false",
                "marketplace.worker-initial-delay=3600000",
                "spring.security.oauth2.client.registration.marketplace.client-id=marketplace",
                "spring.security.oauth2.client.registration.marketplace.client-secret=contract-secret",
                "marketplace.identity.audience=marketplace",
                "marketplace.identity.roles-client=marketplace"
        })
@Import(ProductionAuthenticationIntegrationTest.ProviderConfig.class)
class ProductionAuthenticationIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final RSAKey KEY = key();
    static final Map<String, String> nonces = new ConcurrentHashMap<>();
    static final HttpServer issuer = start();
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate db;

    static String issuerUrl() {
        return "http://127.0.0.1:" + issuer.getAddress().getPort();
    }

    static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("contract-key").generate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ProductionAuthenticationIntegrationTest::handle);
            server.start();
            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String token(
            String subject, String audience, Instant expires, String nonce, List<String> roles)
            throws Exception {
        var builder =
                new JWTClaimsSet.Builder()
                        .issuer(issuerUrl())
                        .subject(subject)
                        .audience(audience)
                        .issueTime(new Date())
                        .expirationTime(Date.from(expires))
                        .claim("resource_access", Map.of("marketplace", Map.of("roles", roles)));
        if (nonce != null) builder.claim("nonce", nonce);
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                        builder.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    static void handle(HttpExchange x) throws java.io.IOException {
        try {
            Object body;
            switch (x.getRequestURI().getPath()) {
                case "/.well-known/openid-configuration" -> body =
                        Map.of(
                                "issuer",
                                issuerUrl(),
                                "authorization_endpoint",
                                issuerUrl() + "/authorize",
                                "token_endpoint",
                                issuerUrl() + "/token",
                                "jwks_uri",
                                issuerUrl() + "/jwks",
                                "userinfo_endpoint",
                                issuerUrl() + "/userinfo",
                                "response_types_supported",
                                List.of("code"),
                                "subject_types_supported",
                                List.of("public"),
                                "id_token_signing_alg_values_supported",
                                List.of("RS256"));
                case "/jwks" -> body = new JWKSet(KEY.toPublicJWK()).toJSONObject();
                case "/authorize" -> {
                    var q = form(x.getRequestURI().getRawQuery());
                    String code = UUID.randomUUID().toString();
                    nonces.put(code, q.get("nonce"));
                    x.getResponseHeaders()
                            .set(
                                    "Location",
                                    q.get("redirect_uri")
                                            + "?code="
                                            + code
                                            + "&state="
                                            + URLEncoder.encode(q.get("state"), StandardCharsets.UTF_8));
                    x.sendResponseHeaders(302, -1);
                    return;
                }
                case "/token" -> {
                    var q = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String nonce = nonces.remove(q.get("code"));
                    if (nonce == null) {
                        x.sendResponseHeaders(400, -1);
                        return;
                    }
                    body =
                            Map.of(
                                    "access_token",
                                    token(
                                            "oidc-alice", "marketplace", Instant.now().plusSeconds(300), null, List.of()),
                                    "token_type",
                                    "Bearer",
                                    "expires_in",
                                    300,
                                    "scope",
                                    "openid profile",
                                    "id_token",
                                    token(
                                            "oidc-alice",
                                            "marketplace",
                                            Instant.now().plusSeconds(300),
                                            nonce,
                                            List.of("SELLER")));
                }
                case "/userinfo" -> body =
                        Map.of(
                                "sub",
                                "oidc-alice",
                                "name",
                                "Alice",
                                "resource_access",
                                Map.of("marketplace", Map.of("roles", List.of("ADMIN"))));
                default -> {
                    x.sendResponseHeaders(404, -1);
                    return;
                }
            }
            byte[] raw = JSON.writeValueAsBytes(body);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, raw.length);
            x.getResponseBody().write(raw);
        } catch (Exception e) {
            x.sendResponseHeaders(500, -1);
        } finally {
            x.close();
        }
    }

    static Map<String, String> form(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&")) {
            var p = pair.split("=", 2);
            result.put(
                    URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add(
                "spring.security.oauth2.client.provider.marketplace.issuer-uri",
                ProductionAuthenticationIntegrationTest::issuerUrl);
    }

    @AfterAll
    static void stop() {
        issuer.stop(0);
    }

    @BeforeEach
    void reset() {
        db.execute("truncate customers cascade");
        db.execute("truncate outbox_events,processed_events,consumer_versions,event_failures");
        db.execute("truncate spring_session cascade");
    }

    HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void login(HttpClient client) throws Exception {
        var start = get(client, port, "/login");
        assertEquals(302, start.statusCode());
        var authorization = get(client, port, "/oauth2/authorization/marketplace");
        assertEquals(302, authorization.statusCode());
        String before =
                ((CookieManager) client.cookieHandler().orElseThrow())
                        .getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        var idp =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(authorization.headers().firstValue("location").orElseThrow()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(302, idp.statusCode());
        var callback =
                client.send(
                        HttpRequest.newBuilder(URI.create(idp.headers().firstValue("location").orElseThrow()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(302, callback.statusCode());
        assertFalse(
                callback.headers().firstValue("location").orElse("").contains("error"),
                callback.toString());
        String after =
                ((CookieManager) client.cookieHandler().orElseThrow())
                        .getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        assertNotEquals(before, after, "Login must rotate the session ID");
    }

    @Test
    void signedBearerAudienceExpiryAndRoleIsolation() throws Exception {
        var c = client(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        for (String audience : List.of("wrong-service", "marketplace")) {
            String jwt =
                    token("api-user", audience, Instant.now().plusSeconds(120), null, List.of("ADMIN"));
            var r =
                    c.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://127.0.0.1:" + port + "/api/v1/admin/sellers"))
                                    .header("Authorization", "Bearer " + jwt)
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(audience.equals("marketplace") ? 200 : 401, r.statusCode());
        }
        var expired =
                c.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/customers/me"))
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + token(
                                                "api-user",
                                                "marketplace",
                                                Instant.now().minusSeconds(120),
                                                null,
                                                List.of()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(401, expired.statusCode());
        assertEquals(
                0,
                db.queryForObject("select count(*) from spring_session", Integer.class),
                "Bearer APIs must not create browser sessions");
    }

    @Test
    void oidcLoginAndSessionSurviveInstanceLossAndRevocation() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var c = client(cookies);
        login(c);
        var me = get(c, port, "/api/v1/customers/me");
        assertEquals(200, me.statusCode(), me.body());
        assertEquals("oidc-alice", JSON.readTree(me.body()).path("profile").path("id").asText());
        assertEquals(
                403,
                get(c, port, "/api/v1/admin/sellers").statusCode(),
                "Untrusted userinfo roles must not grant ADMIN");
        Map<String, String> props =
                Map.ofEntries(
                        Map.entry("server.port", "0"),
                        Map.entry("spring.profiles.active", "production"),
                        Map.entry("spring.datasource.url", pg.getJdbcUrl()),
                        Map.entry("spring.datasource.username", pg.getUsername()),
                        Map.entry("spring.datasource.password", pg.getPassword()),
                        Map.entry("spring.security.oauth2.client.provider.marketplace.issuer-uri", issuerUrl()),
                        Map.entry(
                                "spring.security.oauth2.client.registration.marketplace.client-id", "marketplace"),
                        Map.entry(
                                "spring.security.oauth2.client.registration.marketplace.client-secret",
                                "contract-secret"),
                        Map.entry("marketplace.identity.audience", "marketplace"),
                        Map.entry("marketplace.identity.roles-client", "marketplace"),
                        Map.entry("COOKIE_SECURE", "false"),
                        Map.entry("marketplace.worker-initial-delay", "3600000"));
        try (var second =
                     new SpringApplicationBuilder(MarketplaceApplication.class, ProviderConfig.class)
                             .run(
                                     props.entrySet().stream()
                                             .map(e -> "--" + e.getKey() + "=" + e.getValue())
                                             .toArray(String[]::new))) {
            int other =
                    ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) second)
                            .getWebServer()
                            .getPort();
            assertEquals(
                    200,
                    get(c, other, "/api/v1/customers/me").statusCode(),
                    "Session must load on another instance without sticky routing");
            var csrf = JSON.readTree(get(c, other, "/api/v1/csrf").body());
            var forbidden =
                    c.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + other + "/api/v1/sessions"))
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forbidden.statusCode());
            second.close();
            assertEquals(
                    200,
                    get(c, port, "/api/v1/customers/me").statusCode(),
                    "Session survives the other instance shutting down");
            var revoked =
                    c.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/sessions"))
                                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, revoked.statusCode(), revoked.body());
            assertEquals(401, get(c, port, "/api/v1/customers/me").statusCode());
        }
    }

    @Test
    void oidcCallbackRejectsStateReplayAndDeactivatedJwt() throws Exception {
        var c = client(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        var replay = get(c, port, "/login/oauth2/code/marketplace?code=forged&state=forged");
        assertEquals(302, replay.statusCode());
        assertTrue(replay.headers().firstValue("location").orElse("").contains("error"));
        db.update("insert into customers(id,display_name,active) values ('blocked','Blocked',false)");
        var r =
                c.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/customers/me"))
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + token(
                                                "blocked",
                                                "marketplace",
                                                Instant.now().plusSeconds(120),
                                                null,
                                                List.of()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(403, r.statusCode());
    }

    @Test
    void expiredSharedSessionAndBearerCannotFallBackToCookie() throws Exception {
        var c = client(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        login(c);
        var invalid =
                c.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/customers/me"))
                                .header("Authorization", "Bearer broken")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(401, invalid.statusCode(), "Invalid bearer must not inherit authenticated cookie");
        assertEquals(200, get(c, port, "/api/v1/customers/me").statusCode());
        db.update("update spring_session set expiry_time=0,last_access_time=0");
        assertEquals(401, get(c, port, "/api/v1/customers/me").statusCode());
    }

    @TestConfiguration
    static class ProviderConfig {
        @Bean
        PaymentProvider provider(Store s) {
            return new LocalPaymentProvider(s);
        }
    }
}
