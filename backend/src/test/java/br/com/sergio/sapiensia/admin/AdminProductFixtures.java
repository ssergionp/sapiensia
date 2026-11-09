package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.security.SecretCipher;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Shared setup of the Product tests: the single Administrator with MFA on, and request bodies. */
final class AdminProductFixtures {

    static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    static final String PRODUCTS = "/api/admin/products";

    private AdminProductFixtures() {}

    /** The Administrator of a test: its id, e-mail and TOTP secret. */
    record Admin(UUID id, String email, byte[] secret) {}

    /** Replaces the Administrator table content with one Administrator that has a password and an active MFA. */
    static Admin insertAdmin(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, SecretCipher cipher) {
        jdbc.update("delete from scheduled_tasks where task_name = ?", AdministratorEmailTasks.PASSWORD_SETUP);
        jdbc.update("delete from administrators");
        UUID id = UUID.randomUUID();
        String email = "painel-" + id + "@example.com";
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                id,
                email,
                passwordEncoder.encode(PASSWORD));
        return new Admin(id, email, AdminMfaTestSupport.enableMfa(jdbc, cipher, id));
    }

    static String json(String title, String description, String category, Object priceCents) {
        return "{\"title\":" + quote(title) + ",\"description\":" + quote(description) + ",\"category\":"
                + quote(category) + ",\"priceCents\":" + priceCents + "}";
    }

    static String validBody() {
        return json("Guia de Pix", "Passo a passo para receber por Pix.", "FINANCE", 4990);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
