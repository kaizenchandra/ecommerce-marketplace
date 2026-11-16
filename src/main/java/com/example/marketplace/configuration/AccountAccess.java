package com.example.marketplace.configuration;

import com.example.marketplace.shared.Store;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applied to sessions AND JWTs: deactivation cannot be bypassed with an already-issued token.
 */
@Component
public class AccountAccess extends OncePerRequestFilter {
    private final Store s;

    public AccountAccess(Store s) {
        this.s = s;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            boolean expired =
                    auth.getPrincipal() instanceof OidcUser oidc
                            && (oidc.getIdToken().getExpiresAt() == null
                            || !oidc.getIdToken().getExpiresAt().isAfter(java.time.Instant.now()));
            boolean disabled =
                    s.db.queryForObject(
                            "select exists(select 1 from customers where id=? and not active)",
                            Boolean.class,
                            auth.getName());
            if (expired || disabled) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(expired ? 401 : 403);
                response.setContentType("application/problem+json");
                response
                        .getWriter()
                        .write(
                                "{\"status\":"
                                        + (expired ? 401 : 403)
                                        + ",\"title\":\"Account access expired or revoked\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
