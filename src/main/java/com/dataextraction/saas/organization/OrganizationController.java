package com.dataextraction.saas.organization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dataextraction.saas.security.AccountStore;
import com.dataextraction.saas.security.AppUserPrincipal;
import com.dataextraction.saas.security.UserAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {
    private final AccountStore accounts;
    private final OrganizationStore organizations;
    public OrganizationController(AccountStore accounts, OrganizationStore organizations) { this.accounts = accounts; this.organizations = organizations; }

    @GetMapping
    public Map<String, Object> current(@AuthenticationPrincipal AppUserPrincipal user) {
        return Map.of("organizationId", user.organizationId(), "name", organizations.findById(user.organizationId())
                        .map(WorkspaceOrganization::name).orElseGet(() -> workspaceName(user.organizationId())),
                "role", user.role(), "createdAt", accounts.findById(user.userId()).orElseThrow().createdAt());
    }

    @GetMapping("/members")
    public List<Member> members(@AuthenticationPrincipal AppUserPrincipal user) {
        return accounts.all().stream().filter(account -> account.organizationId().equals(user.organizationId()))
                .map(account -> new Member(account.id(), account.email(), account.displayName(), account.role(), account.createdAt()))
                .toList();
    }

    private String workspaceName(UUID id) {
        if (id.equals(UUID.fromString("00000000-0000-0000-0000-000000000000"))) return "ExtractIQ System Workspace";
        if (id.equals(UUID.fromString("00000000-0000-0000-0000-000000000001"))) return "ExtractIQ Admin Workspace";
        if (id.equals(UUID.fromString("00000000-0000-0000-0000-000000000002"))) return "ExtractIQ User Workspace";
        return "Personal Workspace";
    }
    public record Member(UUID userId, String email, String displayName, String role, java.time.Instant joinedAt) { }
}
