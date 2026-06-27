package com.intentreactor.tools.dynamic.model;

import com.intentreactor.tools.dynamic.api.ScriptStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Persistent record of an LLM-generated ECMAScript 5.1 tool script: name, description,
 * parameter schema, source code, status, and timestamps. Stored by {@link com.intentreactor.tools.dynamic.api.ScriptRepository}.
 */
@Getter
@Setter
public class ScriptDefinition {

    private String id;
    private String name;
    private String version;
    private String description;
    private String code;
    private Map<String, Object> parameterSchema;
    private ScriptStatus status;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean risky = true;

    public ScriptDefinition() {
    }

    public ScriptDefinition(String id, String name, String version,
                            String description, String code,
                            Map<String, Object> parameterSchema) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.code = code;
        this.parameterSchema = parameterSchema;
        this.status = ScriptStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
