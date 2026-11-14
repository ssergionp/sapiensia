package br.com.sergio.sapiensia.payment;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads the CPF only from a JSON string. Any other token is refused with a fixed message: Jackson's own message for a
 * number would carry the digits, and Spring logs it at DEBUG (SEC-033).
 */
final class CpfTextDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (String) context.reportInputMismatch(this, "cpf must be a JSON string");
        }
        return parser.getString();
    }
}
