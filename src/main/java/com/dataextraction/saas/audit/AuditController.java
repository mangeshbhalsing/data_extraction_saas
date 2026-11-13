package com.dataextraction.saas.audit;

import java.util.List;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.security.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditStore store;
    public AuditController(AuditStore store) { this.store = store; }
    @GetMapping
    public List<AuditEvent> latest(@RequestParam(defaultValue = "100") int limit,
                                   @AuthenticationPrincipal AppUserPrincipal user) {
        if (!"ADMIN".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Administrator role is required");
        if (limit < 1 || limit > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit must be 1 to 500");
        return store.latest(limit).stream().filter(event -> event.organizationId().equals(user.organizationId())).toList();
    }
}
