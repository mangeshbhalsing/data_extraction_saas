package com.dataextraction.saas;

import java.util.List;
import java.util.UUID;

import com.dataextraction.saas.security.AppUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TestIdentity {
    public static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private TestIdentity() { }
    public static void install() {
        var principal = new AppUserPrincipal(UUID.fromString("10000000-0000-0000-0000-000000000002"), ORGANIZATION_ID,
                "test@example.com", "not-used", "Test User", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(() -> "ROLE_USER")));
    }
    public static void clear() { SecurityContextHolder.clearContext(); }
}
