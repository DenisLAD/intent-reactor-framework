package com.intentreactor.tools.dynamic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the dynamic scripting subsystem,
 * bound to the {@code intent-reactor.tools.dynamic-scripting} prefix.
 */
@ConfigurationProperties(prefix = "intent-reactor.tools.dynamic-scripting")
public class DynamicScriptingProperties {

    private boolean enabled = false;
    private Duration maxExecutionTime = Duration.ofSeconds(5);
    private List<String> allowedClasses = new ArrayList<>();
    private String scriptRepository = "in-memory";
    private int maxGenerationRetries = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMaxExecutionTime() {
        return maxExecutionTime;
    }

    public void setMaxExecutionTime(Duration maxExecutionTime) {
        this.maxExecutionTime = maxExecutionTime;
    }

    public List<String> getAllowedClasses() {
        return allowedClasses;
    }

    public void setAllowedClasses(List<String> allowedClasses) {
        this.allowedClasses = allowedClasses;
    }

    public String getScriptRepository() {
        return scriptRepository;
    }

    public void setScriptRepository(String scriptRepository) {
        this.scriptRepository = scriptRepository;
    }

    public int getMaxGenerationRetries() {
        return maxGenerationRetries;
    }

    public void setMaxGenerationRetries(int maxGenerationRetries) {
        this.maxGenerationRetries = maxGenerationRetries;
    }
}
