package com.dataextraction.saas.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    public AuthController(AuthService auth, AuthenticationManager authenticationManager,
                          SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
        this.auth = auth;
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<AuthService.UserProfile> register(@RequestBody RegisterRequest request) {
        auth.register(request.email(), request.displayName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/auth/login")
    public AuthService.UserProfile login(@RequestBody LoginRequest request,
                                         HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        if (httpRequest.getSession(false) != null) httpRequest.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, httpRequest, httpResponse);
        return auth.profile((AppUserPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me")
    public AuthService.UserProfile me(Authentication authentication) {
        return auth.profile((AppUserPrincipal) authentication.getPrincipal());
    }

    public record RegisterRequest(String email, String displayName, String password) { }
    public record LoginRequest(String email, String password) { }
}
