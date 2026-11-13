package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.demo.DemoAccounts;
import java.util.UUID;
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
 * Local demo only: creates the demo Customer with the e-mail already confirmed. first_login_at stays null so the first
 * login sends no new-device e-mail. Never logs the e-mail or password.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
@Order(2)
class DemoCustomerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCustomerSeeder.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    DemoCustomerSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder, Environment environment) {
        DemoAccounts.refuseInProd(environment);
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("select count(*) from customers where email = :email")
                        .param("email", DemoAccounts.CUSTOMER_EMAIL)
                        .query(Long.class)
                        .single()
                > 0;
        if (exists) {
            log.info("demo data: customer exists");
            return;
        }
        jdbc.sql("insert into customers (id, email, password_hash, email_confirmed_at)"
                        + " values (:id, :email, :hash, now())")
                .param("id", UUID.randomUUID())
                .param("email", DemoAccounts.CUSTOMER_EMAIL)
                .param("hash", passwordEncoder.encode(DemoAccounts.CUSTOMER_PASSWORD))
                .update();
        log.info("demo data: customer created");
    }
}
