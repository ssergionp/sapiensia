package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.demo.DemoAccounts;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.util.UUID;
import org.bouncycastle.util.encoders.Base32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Local demo only: creates the demo Administrator with a password and MFA already enrolled. Runs before the bootstrap
 * runner, which then finds a password and sends no first-access e-mail. Never logs the e-mail, password or secret.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
@Order(1)
class DemoAdministratorSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAdministratorSeeder.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecretCipher cipher;

    DemoAdministratorSeeder(
            JdbcClient jdbc, PasswordEncoder passwordEncoder, SecretCipher cipher, Environment environment) {
        DemoAccounts.refuseInProd(environment);
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("select count(*) from administrators")
                        .query(Long.class)
                        .single()
                > 0;
        if (exists) {
            log.info("demo data: administrator exists");
            return;
        }
        UUID id = UUID.randomUUID();
        byte[] secret = Base32.decode(DemoAccounts.ADMIN_TOTP_SECRET);
        jdbc.sql("insert into administrators (id, email, password_hash, totp_secret, totp_enabled_at)"
                        + " values (:id, :email, :hash, :secret, now())")
                .param("id", id)
                .param("email", DemoAccounts.ADMIN_EMAIL)
                .param("hash", passwordEncoder.encode(DemoAccounts.ADMIN_PASSWORD))
                .param("secret", cipher.encrypt(secret, AdministratorMfa.secretAad(id)))
                .update();
        log.info("demo data: administrator created");
    }
}
