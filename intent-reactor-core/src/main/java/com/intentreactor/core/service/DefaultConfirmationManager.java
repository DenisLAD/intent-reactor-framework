package com.intentreactor.core.service;

import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.ConfirmationRequest;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.core.config.IntentReactorProperties;

import java.util.Map;
import java.util.UUID;

public class DefaultConfirmationManager implements ConfirmationManager {

    private final IntentReactorProperties properties;

    public DefaultConfirmationManager(IntentReactorProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean needsConfirmation(Tool tool) {
        return !properties.getPlanning().isAutonomous() && tool.isRisky();
    }

    @Override
    public ConfirmationRequest buildRequest(PlanStep step) {
        String actionId = UUID.randomUUID().toString();
        String toolName = step.action() != null ? step.action().toolName() : "unknown";
        Map<String, Object> params = step.action() != null ? step.action().parameters() : Map.of();
        return new ConfirmationRequest(actionId, toolName, step.description(), params);
    }
}
