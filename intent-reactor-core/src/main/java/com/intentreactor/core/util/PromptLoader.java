package com.intentreactor.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    public String load(String resourcePath) {
        return load(resourcePath, Map.of());
    }

    public String load(String resourcePath, Map<String, Object> vars) {
        try {
            Resource resource = resourceLoader.getResource(resourcePath);
            if (!resource.exists()) {
                log.warn("Prompt resource not found: {}", resourcePath);
                return "";
            }
            String template = resource.getContentAsString(StandardCharsets.UTF_8);
            if (template.isBlank()) return "";
            return render(template, vars);
        } catch (IOException e) {
            log.warn("Could not load prompt from {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }

    private String render(String template, Map<String, Object> vars) {
        Map<String, Object> all = new HashMap<>();
        all.put("currentDate", LocalDateTime.now().toString());
        all.putAll(vars);
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            if (entry.getValue() != null) {
                template = template.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }
        if (log.isWarnEnabled()) {
            Matcher m = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}").matcher(template);
            if (m.find()) {
                log.warn("Unreplaced placeholder '{}' found in rendered prompt", m.group());
            }
        }
        return template;
    }
}
