package com.dataextraction.saas.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record AppUserPrincipal(UUID userId, UUID organizationId, String email, String password,
                              String displayName, String role, boolean enabled) implements UserDetails {
    public AppUserPrincipal(UUID userId, UUID organizationId, String email, String password,
                            String displayName, String role) {
        this(userId, organizationId, email, password, displayName, role, true);
    }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return password; }
    @Override public boolean isEnabled() { return enabled; }
}
