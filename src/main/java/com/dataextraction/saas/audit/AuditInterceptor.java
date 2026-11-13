package com.dataextraction.saas.audit;

import java.time.Instant;
import java.util.UUID;

import com.dataextraction.saas.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuditInterceptor implements HandlerInterceptor {
    private final AuditStore store;
    public AuditInterceptor(AuditStore store) { this.store = store; }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || !request.getRequestURI().startsWith("/api/v1/")) return;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal user)) return;
        String uri = request.getRequestURI().replaceAll("[0-9a-fA-F-]{36}", "{id}");
        store.append(new AuditEvent(UUID.randomUUID(), user.userId(), user.organizationId(), method, uri,
                response.getStatus(), Instant.now()));
    }
}
