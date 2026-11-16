package com.example.marketplace.configuration;

import com.example.marketplace.shared.Store;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SessionApi {
    private final Store s;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionApi(Store s, FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.s = s;
        this.sessions = sessions;
    }

    @GetMapping("/sessions")
    Object list() {
        return sessions.findByPrincipalName(s.user()).values().stream()
                .map(
                        session ->
                                Map.of(
                                        "createdAt",
                                        session.getCreationTime(),
                                        "lastAccessedAt",
                                        session.getLastAccessedTime(),
                                        "expiresAfterSeconds",
                                        session.getMaxInactiveInterval().toSeconds()))
                .toList();
    }

    @DeleteMapping("/sessions")
    Object revoke(HttpServletRequest request) {
        sessions.findByPrincipalName(s.user()).keySet().forEach(sessions::deleteById);
        var current = request.getSession(false);
        if (current != null) current.invalidate();
        return Map.of("revoked", true);
    }

    @PostMapping("/admin/customers/{subject}/deactivate")
    @Transactional
    Object deactivate(@PathVariable String subject) {
        s.db.update("update customers set active=false where id=?", subject);
        sessions.findByPrincipalName(subject).keySet().forEach(sessions::deleteById);
        s.audit("CUSTOMER_DEACTIVATED", subject);
        return Map.of("deactivated", true);
    }
}
