package com.example.marketplace.shared;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

public final class ProviderHttp {
    private ProviderHttp() {
    }

    public static RestClient client(String base, String allowed, String authorization, long timeout) {
        URI uri = URI.create(base);
        Rules.require(
                base.equals(allowed)
                        || (uri.getScheme().equals("http")
                        && Set.of("127.0.0.1", "localhost").contains(uri.getHost())),
                "Untrusted provider endpoint");
        Rules.require(timeout >= 100 && timeout <= 10000, "Invalid provider timeout");
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(2))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return RestClient.builder()
                .baseUrl(base)
                .requestFactory(factory)
                .defaultHeader("Authorization", authorization)
                .build();
    }
}
