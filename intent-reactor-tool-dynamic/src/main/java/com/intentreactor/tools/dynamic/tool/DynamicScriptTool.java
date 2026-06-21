package com.intentreactor.tools.dynamic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.tools.dynamic.api.ScriptRepository;
import com.intentreactor.tools.dynamic.config.DynamicScriptingProperties;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import com.intentreactor.tools.dynamic.sandbox.RhinoSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class DynamicScriptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DynamicScriptTool.class);

    private final ChatClient chatClient;
    private final ScriptRepository scriptRepository;
    private final RhinoSandbox rhinoSandbox;
    private final ObjectMapper objectMapper;
    private final DynamicScriptingProperties properties;

    public DynamicScriptTool(ChatClient chatClient,
                             ScriptRepository scriptRepository,
                             RhinoSandbox rhinoSandbox,
                             ObjectMapper objectMapper,
                             DynamicScriptingProperties properties) {
        this.chatClient = chatClient;
        this.scriptRepository = scriptRepository;
        this.rhinoSandbox = rhinoSandbox;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "dynamic_script_tool";
    }

    @Override
    public String getDescription() {
        return "Manages dynamic JavaScript tools. "
                + "Operations: 'create' — generate a new JS tool from a description; "
                + "'adapt' — modify an existing script for new requirements; "
                + "'list' — show all active dynamic scripts. "
                + "Parameters: operation (create|adapt|list), scriptName (create), "
                + "description (create/adapt), existingScriptId (adapt), sampleData (optional).";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "operation", Map.of("type", "string",
                                "enum", List.of("create", "adapt", "list")),
                        "scriptName", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "existingScriptId", Map.of("type", "string"),
                        "sampleData", Map.of("type", "string")
                ),
                "required", List.of("operation")
        );
    }

    @Override
    public boolean isRisky() {
        return false;
    }

    @Override
    public boolean isGenerator() {
        return true;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Map<String, Object> params = input.getParameters();
        String operation = (String) params.get("operation");
        if (operation == null) {
            return ToolResult.error("Parameter 'operation' is required (create|adapt|list)");
        }
        return switch (operation) {
            case "create" -> handleCreate(params);
            case "adapt" -> handleAdapt(params);
            case "list" -> handleList();
            default -> ToolResult.error("Unknown operation: " + operation);
        };
    }

    private ToolResult handleCreate(Map<String, Object> params) {
        String scriptName = (String) params.get("scriptName");
        String description = (String) params.get("description");
        String sampleData = (String) params.get("sampleData");

        if (scriptName == null || scriptName.isBlank())
            return ToolResult.error("Parameter 'scriptName' is required for 'create'");
        if (description == null || description.isBlank())
            return ToolResult.error("Parameter 'description' is required for 'create'");

        Optional<ScriptDefinition> existing = scriptRepository.findByName(scriptName);
        if (existing.isPresent()) {
            return ToolResult.error("Script '" + scriptName + "' already exists (id="
                    + existing.get().getId() + "). Use 'adapt' to modify it.");
        }

        try {
            String prompt = buildCreatePrompt(scriptName, description, sampleData);
            int maxRetries = properties.getMaxGenerationRetries();
            String jsCode = null;
            String lastError = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                String llmResponse = chatClient.prompt().user(prompt).call().content();
                jsCode = extractJsCode(llmResponse);

                try {
                    rhinoSandbox.validate(jsCode, scriptName);
                    Map<String, Object> schema = extractSchema(llmResponse, description);
                    ScriptDefinition def = new ScriptDefinition(
                            UUID.randomUUID().toString(), scriptName, "v1", description, jsCode, schema);
                    scriptRepository.save(def);
                    log.info("Created dynamic script '{}' (id={}) on attempt {}", scriptName, def.getId(), attempt + 1);
                    return ToolResult.ok("Script '" + scriptName + "' created successfully (id=" + def.getId()
                            + "). Now available as tool '" + toToolName(scriptName) + "'.");
                } catch (IllegalArgumentException validationError) {
                    lastError = validationError.getMessage();
                    log.warn("Script '{}' generation attempt {}/{} failed: {}", scriptName, attempt + 1, maxRetries + 1, lastError);
                    if (attempt < maxRetries) {
                        prompt = buildFixPrompt(jsCode, lastError);
                    }
                }
            }
            return ToolResult.error("Could not generate valid script '" + scriptName
                    + "' after " + (maxRetries + 1) + " attempts. Last error: " + lastError);

        } catch (Exception e) {
            log.error("Failed to create script '{}'", scriptName, e);
            return ToolResult.error("Script creation failed: " + e.getMessage());
        }
    }

    private ToolResult handleAdapt(Map<String, Object> params) {
        String existingId = (String) params.get("existingScriptId");
        String description = (String) params.get("description");
        String sampleData = (String) params.get("sampleData");

        if (existingId == null || existingId.isBlank())
            return ToolResult.error("Parameter 'existingScriptId' is required for 'adapt'");
        if (description == null || description.isBlank())
            return ToolResult.error("Parameter 'description' is required for 'adapt'");

        Optional<ScriptDefinition> existingOpt = scriptRepository.findById(existingId);
        if (existingOpt.isEmpty()) {
            existingOpt = scriptRepository.findByName(existingId);
        }
        if (existingOpt.isEmpty()) {
            List<ScriptDefinition> similar = scriptRepository.findSimilar(existingId);
            if (!similar.isEmpty()) {
                existingOpt = Optional.of(similar.get(0));
                log.info("Script '{}' not found by id/name, using similar: '{}' (id={})",
                        existingId, existingOpt.get().getName(), existingOpt.get().getId());
            }
        }
        if (existingOpt.isEmpty()) {
            return ToolResult.error("Script not found: '" + existingId + "'. No similar scripts found either.");
        }

        ScriptDefinition existing = existingOpt.get();
        try {
            String prompt = buildAdaptPrompt(existing, description, sampleData);
            int maxRetries = properties.getMaxGenerationRetries();
            String jsCode = null;
            String lastError = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                String llmResponse = chatClient.prompt().user(prompt).call().content();
                jsCode = extractJsCode(llmResponse);

                try {
                    rhinoSandbox.validate(jsCode, existing.getName());

                    Map<String, Object> schema = extractSchema(llmResponse, description);
                    String nextVersion = incrementVersion(existing.getVersion());
                    ScriptDefinition adapted = new ScriptDefinition(
                            UUID.randomUUID().toString(), existing.getName(), nextVersion,
                            existing.getDescription() + " [adapted: " + description + "]",
                            jsCode, schema);

                    scriptRepository.archive(existing.getId());
                    scriptRepository.save(adapted);
                    rhinoSandbox.invalidateCache(existing.getId(), existing.getVersion());

                    log.info("Adapted script '{}': {} -> {} (new id={}) on attempt {}",
                            existing.getName(), existing.getVersion(), nextVersion, adapted.getId(), attempt + 1);
                    return ToolResult.ok("Script '" + existing.getName() + "' adapted to version "
                            + nextVersion + " (new id=" + adapted.getId() + ").");
                } catch (IllegalArgumentException validationError) {
                    lastError = validationError.getMessage();
                    log.warn("Script '{}' adaptation attempt {}/{} failed: {}", existing.getName(), attempt + 1, maxRetries + 1, lastError);
                    if (attempt < maxRetries) {
                        prompt = buildFixPrompt(jsCode, lastError);
                    }
                }
            }
            return ToolResult.error("Could not adapt script '" + existing.getName()
                    + "' after " + (maxRetries + 1) + " attempts. Last error: " + lastError);

        } catch (Exception e) {
            log.error("Failed to adapt script '{}'", existingId, e);
            return ToolResult.error("Script adaptation failed: " + e.getMessage());
        }
    }

    private ToolResult handleList() {
        List<ScriptDefinition> active = scriptRepository.findAllActive();
        if (active.isEmpty()) return ToolResult.ok("No active dynamic scripts found.");
        String summary = active.stream()
                .map(s -> String.format("- %s [%s] (id=%s): %s",
                        s.getName(), s.getVersion(), s.getId(), s.getDescription()))
                .collect(Collectors.joining("\n"));
        return ToolResult.ok("Active dynamic scripts (" + active.size() + "):\n" + summary);
    }

    private String buildCreatePrompt(String scriptName, String description, String sampleData) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a JavaScript function for Mozilla Rhino (ECMAScript 5.1).\n\n");
        sb.append("Script name: ").append(scriptName).append("\n");
        sb.append("Purpose: ").append(description).append("\n");
        if (sampleData != null && !sampleData.isBlank()) {
            sb.append("Sample input: ").append(sampleData).append("\n");
            sb.append("CRITICAL: The input object has EXACTLY the properties shown in the sample above.\n");
            sb.append("You MUST use those exact property names in your function (e.g., input.propertyName).\n");
        }
        sb.append("\nEXAMPLE of valid ECMAScript 5.1 structure (use YOUR property names, not these):\n");
        sb.append("```javascript\n");
        sb.append("function execute(input) {\n");
        sb.append("    // Access properties by their exact names from the sample input\n");
        sb.append("    var result = input.value1 + input.value2;\n");
        sb.append("    return result.toString();\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("REQUIREMENTS:\n");
        sb.append("1. Define exactly: function execute(input) { ... }\n");
        sb.append("   'input' is a JS object — access properties with input.propertyName\n");
        sb.append("2. Return a string or number as the result\n");
        sb.append("3. NO java.io, java.net, java.lang.System, Runtime, Thread\n");
        sb.append("4. ECMAScript 5.1 ONLY — FORBIDDEN: arrow functions (=>), let/const, template literals (`), for...of, destructuring\n");
        sb.append("5. Pure JavaScript logic only\n\n");
        sb.append("After the JS code, add a SCHEMA line listing ALL input parameters with their types:\n");
        sb.append("SCHEMA: {\"type\":\"object\",\"properties\":{\"paramName\":{\"type\":\"number\"},...},\"required\":[\"paramName\",...]}\n\n");
        sb.append("Respond with ONLY the code block and SCHEMA line. No explanations.");
        return sb.toString();
    }

    private String buildFixPrompt(String failedCode, String error) {
        return "The JavaScript code you generated has a syntax error in Mozilla Rhino.\n\n"
                + "Error: " + error + "\n\n"
                + "Your code:\n```javascript\n" + failedCode + "\n```\n\n"
                + "Fix the syntax error. Rhino uses ECMAScript 5.1 — common mistakes:\n"
                + "- Use `function() {}` NOT arrow functions (`=>`)\n"
                + "- Use `var` NOT `let` or `const`\n"
                + "- Use string concatenation (`+`) NOT template literals (backticks)\n"
                + "- Use regular `for` loops NOT `for...of`\n"
                + "- Use `obj.property` NOT destructuring `{ property }`\n\n"
                + "Respond with ONLY the corrected code block and SCHEMA line.";
    }

    private String buildAdaptPrompt(ScriptDefinition existing, String description, String sampleData) {
        StringBuilder sb = new StringBuilder();
        sb.append("Adapt this JavaScript function for Mozilla Rhino (ECMAScript 5.1).\n\n");
        sb.append("EXISTING SCRIPT ('").append(existing.getName()).append("', ")
                .append(existing.getVersion()).append("):\n");
        sb.append("```javascript\n").append(existing.getCode()).append("\n```\n\n");
        sb.append("ADAPTATION REQUIRED: ").append(description).append("\n");
        if (sampleData != null && !sampleData.isBlank()) {
            sb.append("New sample input: ").append(sampleData).append("\n");
        }
        sb.append("\nKeep function execute(input), ECMAScript 5.1, no Java access.\n");
        sb.append("After code add: SCHEMA: {...}\n");
        sb.append("Respond with ONLY the code and SCHEMA line.");
        return sb.toString();
    }

    private String extractJsCode(String llmResponse) {
        int start = llmResponse.indexOf("```javascript\n");
        if (start >= 0) {
            start += "```javascript\n".length();
        } else {
            start = llmResponse.indexOf("```\n");
            if (start >= 0) start += "```\n".length();
        }
        if (start < 0) {
            // No markdown block — look for function execute(
            int funcIdx = llmResponse.indexOf("function execute(");
            if (funcIdx >= 0) {
                int schemaIdx = llmResponse.indexOf("SCHEMA:", funcIdx);
                String code = schemaIdx > funcIdx
                        ? llmResponse.substring(funcIdx, schemaIdx)
                        : llmResponse.substring(funcIdx);
                return code.trim();
            }
            // Last resort: everything before SCHEMA:
            int schemaIdx = llmResponse.indexOf("SCHEMA:");
            if (schemaIdx > 0) return llmResponse.substring(0, schemaIdx).trim();
            throw new IllegalArgumentException("Could not extract JavaScript function from LLM response");
        }
        int end = llmResponse.indexOf("```", start);
        if (end < 0) end = llmResponse.length();
        return llmResponse.substring(start, end).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSchema(String llmResponse, String description) {
        try {
            int schemaIdx = llmResponse.indexOf("SCHEMA:");
            if (schemaIdx >= 0) {
                String schemaJson = llmResponse.substring(schemaIdx + "SCHEMA:".length()).trim();
                int end = findMatchingBrace(schemaJson, 0);
                if (end > 0) schemaJson = schemaJson.substring(0, end + 1);
                return objectMapper.readValue(schemaJson, Map.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse SCHEMA line for '{}': {}", description, e.getMessage());
        }
        return Map.of("type", "object", "properties", Map.of(), "description", description);
    }

    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String incrementVersion(String version) {
        if (version != null && version.startsWith("v")) {
            try {
                return "v" + (Integer.parseInt(version.substring(1)) + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return "v2";
    }

    private String toToolName(String scriptName) {
        return scriptName.replace('-', '_').replace(' ', '_');
    }
}
