package com.dataextraction.saas.security;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.organization.OrganizationStore;
import com.dataextraction.saas.organization.WorkspaceOrganization;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private final AccountStore accounts;
    private final OrganizationStore organizations;
    private final PasswordEncoder passwords;

    public AdminUserService(AccountStore accounts, OrganizationStore organizations, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.passwords = passwords;
    }

    public List<UserAccess> list(AppUserPrincipal actor) {
        requireAdmin(actor);
        return accounts.all().stream()
                .filter(user -> "SUPER_ADMIN".equals(actor.role()) || user.organizationId().equals(actor.organizationId()))
                .sorted(Comparator.comparing(UserAccount::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(this::view)
                .toList();
    }

    @Transactional
    public synchronized UserAccess createUser(CreateUser request, AppUserPrincipal actor) {
        requireAdmin(actor);
        validatePerson(request == null ? null : request.email(), request == null ? null : request.displayName(),
                request == null ? null : request.password());
        String role = request.role() == null || request.role().isBlank() ? "USER" : request.role().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("USER", "ADMIN").contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ROLE", "New users can be assigned USER or ADMIN roles");
        }
        UUID organizationId;
        if ("SUPER_ADMIN".equals(actor.role())) {
            if (request.organizationId() == null || organizations.findById(request.organizationId()).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_REQUIRED", "Choose an existing organization for this user");
            }
            organizationId = request.organizationId();
        } else {
            if (!"USER".equals(role)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED", "Only a super administrator can create organization administrators");
            }
            organizationId = actor.organizationId();
        }
        synchronized (accounts) {
            if (accounts.findByEmail(request.email()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
            }
            UserAccount created = new UserAccount(UUID.randomUUID(), organizationId, request.email().trim(), passwords.encode(request.password()),
                    request.displayName().trim(), role, true, Instant.now());
            return view(accounts.save(created));
        }
    }

    @Transactional
    public synchronized OrganizationCreated createOrganization(CreateOrganization request, AppUserPrincipal actor) {
        requireSuperAdmin(actor);
        if (request == null || request.name() == null || request.name().isBlank() || request.name().trim().length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORGANIZATION_NAME", "Organization name must contain 1 to 120 characters");
        }
        validatePerson(request.adminEmail(), request.adminDisplayName(), request.adminPassword());
        synchronized (accounts) {
            if (accounts.findByEmail(request.adminEmail()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
            }
            Instant now = Instant.now();
            UUID organizationId = UUID.randomUUID();
            UUID adminId = UUID.randomUUID();
            String name = request.name().trim();
            organizations.save(new WorkspaceOrganization(organizationId, name, actor.userId(), now));
            UserAccount admin = new UserAccount(adminId, organizationId, request.adminEmail().trim(), passwords.encode(request.adminPassword()),
                    request.adminDisplayName().trim(), "ADMIN", true, true, now);
            accounts.save(admin);
            return new OrganizationCreated(organizationId, name, adminId, admin.email(), now);
        }
    }

    public List<WorkspaceOrganization> listOrganizations(AppUserPrincipal actor) {
        requireSuperAdmin(actor);
        return organizations.all();
    }

    public synchronized UserAccess update(UUID userId, UpdateAccess request, AppUserPrincipal actor) {
        requireAdmin(actor);
        if (request == null || (request.enabled() == null && request.role() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ACCESS_UPDATE", "Provide an enabled value, a role, or both");
        }
        UserAccount current = accounts.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        if ("SUPER_ADMIN".equals(current.role()) || (!"SUPER_ADMIN".equals(actor.role())
                && !current.organizationId().equals(actor.organizationId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found");
        }
        requireUnprotected(current);
        String role = request.role() == null ? current.role() : request.role().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ADMIN", "USER").contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ROLE", "Role must be ADMIN or USER");
        }
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        if (current.id().equals(actor.userId()) && (!enabled || !"ADMIN".equals(role))) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_REVOKE_OWN_ADMIN_ACCESS", "You cannot disable or demote your own admin account");
        }
        if ("ADMIN".equals(current.role()) && current.enabled() && (!enabled || !"ADMIN".equals(role))) {
            long activeAdmins = accounts.all().stream().filter(user -> user.organizationId().equals(current.organizationId())
                    && user.enabled() && "ADMIN".equals(user.role())).count();
            if (activeAdmins <= 1) {
                throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "The last active administrator cannot be disabled or demoted");
            }
        }
        return view(accounts.save(new UserAccount(current.id(), current.organizationId(), current.email(), current.passwordHash(),
                current.displayName(), role, enabled, current.protectedAdmin(), current.createdAt())));
    }

    @Transactional
    public synchronized UserAccess updateUser(UUID userId, UpdateUser request, AppUserPrincipal actor) {
        requireAdmin(actor);
        if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_UPDATE", "Provide the user's name and email");
        validateIdentity(request.email(), request.displayName());
        UserAccount current = manageableUser(userId, actor);
        requireUnprotected(current);
        accounts.findByEmail(request.email()).filter(existing -> !existing.id().equals(userId)).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        });
        String passwordHash = current.passwordHash();
        if (request.password() != null && !request.password().isBlank()) {
            validatePassword(request.password());
            passwordHash = passwords.encode(request.password());
        }
        UserAccount updated = new UserAccount(current.id(), current.organizationId(), request.email().trim(), passwordHash,
                request.displayName().trim(), current.role(), current.enabled(), current.protectedAdmin(), current.createdAt());
        return view(accounts.save(updated));
    }

    @Transactional
    public synchronized void deleteUser(UUID userId, AppUserPrincipal actor) {
        requireAdmin(actor);
        UserAccount current = manageableUser(userId, actor);
        if (current.id().equals(actor.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DELETE_SELF", "You cannot delete your own account");
        }
        requireUnprotected(current);
        if ("ADMIN".equals(current.role()) && current.enabled() && activeAdminCount(current.organizationId()) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "The last active administrator cannot be deleted");
        }
        accounts.delete(current);
    }

    private UserAccount manageableUser(UUID userId, AppUserPrincipal actor) {
        UserAccount current = accounts.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        if ("SUPER_ADMIN".equals(current.role()) || (!"SUPER_ADMIN".equals(actor.role())
                && !current.organizationId().equals(actor.organizationId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found");
        }
        return current;
    }

    private long activeAdminCount(UUID organizationId) {
        return accounts.all().stream().filter(user -> user.organizationId().equals(organizationId)
                && user.enabled() && "ADMIN".equals(user.role())).count();
    }

    private void requireUnprotected(UserAccount user) {
        if (user.protectedAdmin()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROTECTED_ORGANIZATION_ADMIN", "The first administrator created for this organization is protected");
        }
    }

    private UserAccess view(UserAccount user) {
        String organizationName = organizations.findById(user.organizationId()).map(WorkspaceOrganization::name).orElse("Workspace");
        return new UserAccess(user.id(), user.organizationId(), organizationName, user.email(), user.displayName(), user.role(), user.enabled(), user.protectedAdmin(), user.createdAt());
    }

    private void requireAdmin(AppUserPrincipal actor) {
        if (actor == null || !List.of("ADMIN", "SUPER_ADMIN").contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Administrator role is required");
        }
    }

    private void requireSuperAdmin(AppUserPrincipal actor) {
        if (actor == null || !"SUPER_ADMIN".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED", "Super administrator role is required");
        }
    }

    private void validatePerson(String email, String displayName, String password) {
        validateIdentity(email, displayName);
        validatePassword(password);
    }

    private void validateIdentity(String email, String displayName) {
        if (email == null || !email.matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "A valid email is required");
        }
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Name must contain 1 to 100 characters");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
                || password.chars().noneMatch(Character::isUpperCase)
                || password.chars().noneMatch(Character::isLowerCase)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "Password must be 12 to 128 characters and include upper-case, lower-case, and numeric characters");
        }
    }

    public record UpdateAccess(Boolean enabled, String role) { }
    public record UpdateUser(String email, String displayName, String password) { }
    public record CreateUser(String email, String displayName, String password, String role, UUID organizationId) { }
    public record CreateOrganization(String name, String adminEmail, String adminDisplayName, String adminPassword) { }
    public record OrganizationCreated(UUID organizationId, String name, UUID adminUserId, String adminEmail, Instant createdAt) { }
    public record UserAccess(UUID userId, UUID organizationId, String organizationName, String email, String displayName,
                             String role, boolean enabled, boolean protectedAdmin, java.time.Instant createdAt) { }
}
