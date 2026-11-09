package br.com.sergio.sapiensia.product;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a price in cents only from a JSON integer: a string such as {@code "1000"} is refused instead of coerced
 * (floats like 500.9 and 1e3 are already refused by {@code accept-float-as-int: false}).
 */
final class StrictCentsDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
