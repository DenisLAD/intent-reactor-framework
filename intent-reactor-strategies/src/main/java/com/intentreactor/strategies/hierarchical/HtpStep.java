package com.intentreactor.strategies.hierarchical;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class HtpStep {

    private String toolName;
    private Map<String, Object> parameters = new HashMap<>();
    private String description;
    private String observation;

    public HtpStep() {
    }

    public HtpStep(String toolName, Map<String, Object> parameters, String description) {
        this.toolName = toolName;
        this.parameters = parameters != null ? parameters : new HashMap<>();
        this.description = description;
    }
}
