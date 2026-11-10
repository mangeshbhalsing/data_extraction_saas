package com.dataextraction.saas.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refreshes stored session authorities so admin access changes apply to existing sessions immediately. */
public class AccountAccessFilter extends OncePerRequestFilter {
    private final AccountStore accounts;
    private final SecurityContextRepository contexts;

    public AccountAccessFilter(AccountStore accounts, SecurityContextRepository contexts) {
        this.accounts = accounts;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            UserAccount current = accounts.findById(principal.userId()).orElse(null);
            if (current == null || !current.enabled()) {
                SecurityContext empty = SecurityContextHolder.createEmptyContext();
                SecurityContextHolder.setContext(empty);
                contexts.saveContext(empty, request, response);
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":401,\"code\":\"ACCOUNT_DISABLED\",\"message\":\"This account is disabled. Contact an administrator.\"}");
                return;
            }
            if (!principal.organizationId().equals(current.organizationId())
                    || !principal.role().equals(current.role()) || !principal.displayName().equals(current.displayName())) {
                AppUserPrincipal refreshed = new AppUserPrincipal(current.id(), current.organizationId(), current.email(),
                        current.passwordHash(), current.displayName(), current.role(), current.enabled());
                UsernamePasswordAuthenticationToken updated = UsernamePasswordAuthenticationToken.authenticated(
                        refreshed, null, refreshed.getAuthorities());
                updated.setDetails(authentication.getDetails());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(updated);
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
