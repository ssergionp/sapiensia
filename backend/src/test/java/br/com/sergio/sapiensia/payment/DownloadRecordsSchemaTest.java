package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The Download record (ADR-0007) keeps only Customer, Product, File version and date, and is append-only. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DownloadRecordsSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("ADR-0007: o Registro de download guarda só Cliente, Produto, versão do Arquivo e data")
    void shouldKeepOnlyCustomerProductFileVersionAndDate() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'download_records'",
                String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder("id", "customer_id", "product_id", "file_uploaded_at", "downloaded_at");
    }

    // Rolled back: the table is insert-only, so a committed row would pin its Customer and Product forever and break
    // the tests that delete every Product. Each refusal runs under a savepoint because PostgreSQL aborts the
    // transaction on an error.
    @Test
    @Transactional
    @DisplayName("ADR-0007: o Registro de download só recebe linhas novas")
    void shouldOnlyAcceptNewRows() {
        UUID customerId = OrderTestSupport.insertCustomer(jdbc);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia", 1000);
        jdbc.update(
                "insert into download_records (customer_id, product_id, file_uploaded_at, downloaded_at)"
                        + " values (?, ?, now(), now())",
                customerId,
                productId);

        assertRefused(() ->
                jdbc.update("update download_records set downloaded_at = now() where customer_id = ?", customerId));
        assertRefused(() -> jdbc.update("delete from download_records where customer_id = ?", customerId));
        assertRefused(() -> jdbc.execute("truncate table download_records"));
    }

    private void assertRefused(ThrowingCallable attempt) {
        jdbc.execute("savepoint refusal");
        assertThatThrownBy(attempt).rootCause().isInstanceOfSatisfying(PSQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getServerErrorMessage().getMessage()).isEqualTo("download_records is append-only");
        });
        jdbc.execute("rollback to savepoint refusal");
    }
}
