package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Returns the current date and time, optionally formatted via the {@code format} parameter
 * (defaults to ISO-8601 if omitted).
 */
@Component
public class DateTimeTool implements Tool {

    @Override
    public String getName() {
        return "datetime";
    }

    @Override
    public String getDescription() {
        return "Returns the current date and time. Optional parameter 'format' specifies the output format (default: ISO_LOCAL_DATE_TIME).";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "format", Map.of("type", "string", "description", "DateTimeFormatter pattern, e.g. 'dd.MM.yyyy HH:mm'")
                )
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String format = (String) input.getParameters().getOrDefault("format", null);
        LocalDateTime now = LocalDateTime.now();
        String result = format != null
                ? now.format(DateTimeFormatter.ofPattern(format))
                : now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return ToolResult.ok(result);
    }

    @Override
    public boolean isRisky() {
        return false;
    }
}
