package com.dataextraction.saas.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final AccountStore accounts;

    public AccountUserDetailsService(AccountStore accounts) { this.accounts = accounts; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = accounts.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
        return new AppUserPrincipal(account.id(), account.organizationId(), account.email(),
                account.passwordHash(), account.displayName(), account.role(), account.enabled());
    }
}
