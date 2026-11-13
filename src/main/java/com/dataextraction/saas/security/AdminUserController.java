package com.dataextraction.saas.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) { this.users = users; }

    @GetMapping
    public List<AdminUserService.UserAccess> list(@AuthenticationPrincipal AppUserPrincipal actor) {
        return users.list(actor);
    }

    @GetMapping("/organizations")
    public List<com.dataextraction.saas.organization.WorkspaceOrganization> organizations(@AuthenticationPrincipal AppUserPrincipal actor) {
        return users.listOrganizations(actor);
    }

    @org.springframework.web.bind.annotation.PostMapping
    public ResponseEntity<AdminUserService.UserAccess> create(@RequestBody AdminUserService.CreateUser request,
                                                               @AuthenticationPrincipal AppUserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.createUser(request, actor));
    }

    @org.springframework.web.bind.annotation.PostMapping("/organizations")
    public ResponseEntity<AdminUserService.OrganizationCreated> createOrganization(
            @RequestBody AdminUserService.CreateOrganization request,
            @AuthenticationPrincipal AppUserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.createOrganization(request, actor));
    }

    @PatchMapping("/{userId}/access")
    public AdminUserService.UserAccess update(@PathVariable UUID userId,
                                              @RequestBody AdminUserService.UpdateAccess request,
                                              @AuthenticationPrincipal AppUserPrincipal actor) {
        return users.update(userId, request, actor);
    }

    @PutMapping("/{userId}")
    public AdminUserService.UserAccess updateUser(@PathVariable UUID userId,
                                                  @RequestBody AdminUserService.UpdateUser request,
                                                  @AuthenticationPrincipal AppUserPrincipal actor) {
        return users.updateUser(userId, request, actor);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID userId,
                                           @AuthenticationPrincipal AppUserPrincipal actor) {
        users.deleteUser(userId, actor);
        return ResponseEntity.noContent().build();
    }
}
