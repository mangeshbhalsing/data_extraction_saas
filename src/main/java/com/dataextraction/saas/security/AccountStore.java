package com.dataextraction.saas.security;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountStore {
    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public AccountStore() { this.jdbc = null; }
    @Autowired public AccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UserAccount> findByEmail(String email) {
        if (jdbc != null) return jdbc.query("SELECT * FROM accounts WHERE email_normalized = ?", this::map, normalize(email)).stream().findFirst();
        return Optional.ofNullable(users.get(normalize(email)));
    }

    public Optional<UserAccount> findById(UUID id) {
        if (jdbc != null) return jdbc.query("SELECT * FROM accounts WHERE user_id = ?", this::map, id.toString()).stream().findFirst();
        return users.values().stream().filter(user -> user.id().equals(id)).findFirst();
    }

    public UserAccount save(UserAccount user) {
        if (jdbc != null) {
            jdbc.update("MERGE INTO accounts (user_id, organization_id, email, email_normalized, password_hash, display_name, role, enabled, protected_admin, created_at) KEY(user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    user.id().toString(), user.organizationId().toString(), user.email(), normalize(user.email()), user.passwordHash(),
                    user.displayName(), user.role(), user.enabled(), user.protectedAdmin(), user.createdAt().toEpochMilli());
            return user;
        }
        users.put(normalize(user.email()), user);
        return user;
    }

    public Collection<UserAccount> all() {
        return jdbc == null ? users.values() : jdbc.query("SELECT * FROM accounts ORDER BY created_at", this::map);
    }
    public void delete(UserAccount user) {
        if (jdbc == null) {
            users.remove(normalize(user.email()));
            return;
        }
        jdbc.update("DELETE FROM SPRING_SESSION WHERE LOWER(PRINCIPAL_NAME) = LOWER(?)", user.email());
        jdbc.update("DELETE FROM accounts WHERE user_id = ?", user.id().toString());
    }
    public void clear() { if (jdbc == null) users.clear(); else jdbc.update("DELETE FROM accounts"); }

    private UserAccount map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new UserAccount(UUID.fromString(row.getString("user_id")), UUID.fromString(row.getString("organization_id")),
                row.getString("email"), row.getString("password_hash"), row.getString("display_name"), row.getString("role"), row.getBoolean("enabled"),
                row.getBoolean("protected_admin"), java.time.Instant.ofEpochMilli(row.getLong("created_at")));
    }
    private String normalize(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }
}
