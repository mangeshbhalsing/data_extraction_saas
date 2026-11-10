package com.dataextraction.saas.security;

import java.time.Instant;
import java.util.UUID;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.organization.OrganizationStore;
import com.dataextraction.saas.organization.WorkspaceOrganization;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AccountStore accounts;
    private final OrganizationStore organizations;
    private final PasswordEncoder passwords;

    public AuthService(AccountStore accounts, OrganizationStore organizations, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.passwords = passwords;
    }

    @org.springframework.transaction.annotation.Transactional
    public UserAccount register(String email, String displayName, String password) {
        if (email == null || !email.matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "A valid email is required");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "displayName must contain 1 to 100 characters");
        }
        if (password == null || password.length() < 12 || password.length() > 128
                || password.chars().noneMatch(Character::isUpperCase)
                || password.chars().noneMatch(Character::isLowerCase)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "Password must be 12 to 128 characters and include upper-case, lower-case, and numeric characters");
        }
        synchronized (accounts) {
            if (accounts.findByEmail(email).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
            }
            Instant createdAt = Instant.now();
            UUID organizationId = UUID.randomUUID();
            UserAccount account = new UserAccount(UUID.randomUUID(), organizationId, email.trim(),
                    passwords.encode(password), displayName.trim(), "USER", Instant.now());
            organizations.save(new WorkspaceOrganization(organizationId, displayName.trim() + " Workspace", account.id(), createdAt));
            return accounts.save(account);
        }
    }

    public UserProfile profile(AppUserPrincipal principal) {
        return new UserProfile(principal.userId(), principal.organizationId(), principal.email(),
                principal.displayName(), principal.role());
    }

    public record UserProfile(UUID userId, UUID organizationId, String email, String displayName, String role) { }
}
