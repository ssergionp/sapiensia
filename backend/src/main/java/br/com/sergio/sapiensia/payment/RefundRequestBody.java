package br.com.sergio.sapiensia.payment;

import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The body of {@code POST /api/orders/{id}/refund-request}. Neither field may reach a log (SEC-036): Spring logs the
 * messages of a refused body at DEBUG and TRACE, so an unknown reason is refused with a fixed message, and the limit of
 * the comment (500 characters, as sent, before its spaces are stripped) is checked by the service, not by Bean
 * Validation, whose error would carry the refused text.
 */
record RefundRequestBody(
        @NotNull(message = "REASON_REQUIRED") @JsonDeserialize(using = ReasonDeserializer.class)
        RefundReason reason,

        String details) {

    // never the comment: Spring logs the body object through toString at DEBUG (SEC-036)
    @Override
    public String toString() {
        return "RefundRequestBody[reason=" + reason + "]";
    }

    /** Only one of the listed codes, as a JSON string; anything else is refused without echoing it. */
    static final class ReasonDeserializer extends ValueDeserializer<RefundReason> {

        @Override
        public RefundReason deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String text = parser.getString();
                for (RefundReason reason : RefundReason.values()) {
                    if (reason.name().equals(text)) {
                        return reason;
                    }
                }
            }
            return (RefundReason) context.reportInputMismatch(this, "reason must be one of the listed codes");
        }
    }
}
