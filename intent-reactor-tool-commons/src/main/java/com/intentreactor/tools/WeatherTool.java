package com.intentreactor.tools;

import com.intentreactor.api.SimulatableTool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WeatherTool implements SimulatableTool {

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "Returns the current weather forecast for a city. Parameter 'city' is the city name.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "City name"),
                        "units", Map.of("type", "string", "enum", List.of("metric", "imperial"),
                                "description", "Temperature units (default: metric)")
                ),
                "required", List.of("city")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String city = (String) input.getParameters().get("city");
        if (city == null || city.isBlank()) {
            return ToolResult.error("Parameter 'city' is required");
        }
        // Stub implementation — replace with real API call if needed
        String units = (String) input.getParameters().getOrDefault("units", "metric");
        String unit = "metric".equals(units) ? "°C" : "°F";
        return ToolResult.ok(Map.of(
                "city", city,
                "temperature", "18" + unit,
                "condition", "Partly cloudy",
                "humidity", "65%"
        ));
    }

    @Override
    public ToolResult simulate(ToolInput input) {
        // Same as execute since this is a read-only tool
        return execute(input);
    }

    @Override
    public boolean isRisky() {
        return false;
    }
}
