package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminRefundRequestItemTest {

    @Test
    @DisplayName("SEC-036: o toString do item da lista não leva o comentário nem o e-mail")
    void shouldKeepTheCommentAndTheEmailOutOfToString() {
        UUID orderId = UUID.randomUUID();
        String comment = "marker-comment-" + UUID.randomUUID();
        String email = "m***@example.com";
        AdminRefundRequestItem item = new AdminRefundRequestItem(
                orderId,
                "Guia",
                2990,
                email,
                RefundReason.OTHER,
                comment,
                Instant.parse("2026-11-19T18:00:00Z"),
                Instant.parse("2026-11-19T17:00:00Z"),
                0,
                0,
                "pay_123",
                null);
        AdminRefundRequestPage page = new AdminRefundRequestPage(List.of(item), 0, 1);

        assertThat(item.toString())
                .contains(orderId.toString())
                .doesNotContain(comment)
                .doesNotContain(email);
        assertThat(page.toString()).doesNotContain(comment).doesNotContain(email);
    }
}
