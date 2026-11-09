package br.com.sergio.sapiensia.audit;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** Replaces the audit writer with {@link SwitchableAuditLog} (off by default) in the tests that import it. */
@TestConfiguration
public class SwitchableAuditLogConfig {

    @Bean
    @Primary
    public SwitchableAuditLog switchableAuditLog(
            AuditEvents events, Clock clock, PlatformTransactionManager transactionManager) {
        return new SwitchableAuditLog(events, clock, transactionManager);
    }
}
