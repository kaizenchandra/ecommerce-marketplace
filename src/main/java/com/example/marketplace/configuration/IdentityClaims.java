package com.example.marketplace.configuration;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Only this application's client roles grant marketplace privileges. Realm/global roles do not.
 */
public final class IdentityClaims {
    private IdentityClaims() {
    }

    public static Collection<GrantedAuthority> authorities(
            Map<String, Object> claims, String clientId) {
        Set<GrantedAuthority> result = new HashSet<>();
        result.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        Object access = claims.get("resource_access");
        if (access instanceof Map<?, ?> clients
                && clients.get(clientId) instanceof Map<?, ?> client
                && client.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles)
                if (Set.of("CUSTOMER", "SELLER", "ADMIN").contains(role))
                    result.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return result;
    }
}
