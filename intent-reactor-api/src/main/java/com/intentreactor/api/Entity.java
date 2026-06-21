package com.intentreactor.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * A named entity extracted from a user message by {@link IntentPreprocessor}.
 *
 * <p>Entities represent specific values mentioned in the message, such as locations,
 * dates, product names, or order identifiers. They supplement the {@link Intent} with
 * the concrete data the planner needs to parameterise tool calls.
 *
 * @see IntentAnalysisResult
 * @see IntentPreprocessor
 */
@Getter
@Setter
public class Entity {

    private String type;
    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String value;
    private Map<String, Object> metadata;

    /**
     * Required by Jackson for deserialization.
     */
    public Entity() {
    }

    /**
     * Creates an entity with type, extracted value, and optional metadata.
     *
     * @param type     the entity category (e.g., {@code "CITY"}, {@code "DATE"}, {@code "ORDER_ID"})
     * @param value    the raw extracted text (e.g., {@code "Berlin"}, {@code "2024-03-15"})
     * @param metadata additional extractor-specific attributes; may be {@code null}
     */
    public Entity(String type, String value, Map<String, Object> metadata) {
        this.type = type;
        this.value = value;
        this.metadata = metadata;
    }

}
