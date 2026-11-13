package br.com.sergio.sapiensia.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps the values printed by the demo scripts in sync with the accounts the seeders create. */
class DemoScriptTest {

    private static final Path SCRIPTS = Path.of("..", "scripts");

    @Test
    void demoScriptPrintsTheSeededAccounts() throws Exception {
        String script = Files.readString(SCRIPTS.resolve("demo.ps1"));

        assertThat(script)
                .contains(DemoAccounts.ADMIN_EMAIL)
                .contains(DemoAccounts.ADMIN_PASSWORD)
                .contains(DemoAccounts.CUSTOMER_EMAIL)
                .contains(DemoAccounts.CUSTOMER_PASSWORD)
                .contains(DemoAccounts.ADMIN_TOTP_SECRET);
    }

    @Test
    void codeScriptUsesTheSeededTotpSecret() throws Exception {
        String script = Files.readString(SCRIPTS.resolve("demo-codigo.mjs"));

        assertThat(script).contains(DemoAccounts.ADMIN_TOTP_SECRET);
    }
}
