package com.intentreactor.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles LLM responses where a field is sometimes a String, sometimes a JSON array.
 */
public class StringOrArrayDeserializer extends StdDeserializer<String> {

    public StringOrArrayDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<String> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                items.add(p.getValueAsString());
            }
            return String.join(", ", items);
        }
        return p.getValueAsString();
    }
}
