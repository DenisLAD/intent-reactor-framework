package com.intentreactor.core.service.multiintent;

import com.intentreactor.api.Intent;
import com.intentreactor.api.MultiIntentContext;
import com.intentreactor.api.MultiIntentStrategy;
import com.intentreactor.api.PerformedAction;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionAttributeKeys;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.SingleIntentExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link com.intentreactor.api.MultiIntentStrategy} that processes detected intents
 * one at a time in the order returned by the preprocessor.
 */
public class SequentialMultiIntentStrategy implements MultiIntentStrategy {

    private final SessionStore sessionStore;

    public SequentialMultiIntentStrategy(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public String name() {
        return "sequential";
    }

    @Override
    public ReactorResponse execute(SessionState session, MultiIntentContext ctx,
                                   boolean persistent, SingleIntentExecutor executor) {
        while (!ctx.getPendingIntents().isEmpty()) {
            Intent current = ctx.getPendingIntents().remove(0);
            ctx.setCurrentIntent(current);
            session.setPlanState(new PlanState(current.getName()));
            session.getAttributes().put(SessionAttributeKeys.MULTI_INTENT_STATE_KEY, ctx);
            if (persistent) sessionStore.save(session);

            ReactorResponse response = executor.execute(session, persistent);

            // Return early without marking complete — proceedAfterConfirmation will do it
            if (response.getStatus() == PlanStatus.AWAITING_CONFIRMATION) {
                return response;
            }

            ctx.getResults().put(current.getName(), response);
            ctx.getCompletedIntents().add(current);
        }

        return mergeResults(session.getId(), ctx);
    }

    static ReactorResponse mergeResults(String sessionId, MultiIntentContext ctx) {
        List<PerformedAction> allActions = new ArrayList<>();
        StringBuilder finalText = new StringBuilder();
        boolean anyFailed = false;

        for (Map.Entry<String, ReactorResponse> entry : ctx.getResults().entrySet()) {
            ReactorResponse r = entry.getValue();
            if (r.getStatus() == PlanStatus.FAILED) anyFailed = true;
            if (r.getActions() != null) allActions.addAll(r.getActions());
            if (r.getFinalText() != null) {
                if (!finalText.isEmpty()) finalText.append("; ");
                finalText.append("[").append(entry.getKey()).append("] ").append(r.getFinalText());
            }
        }

        return anyFailed
                ? ReactorResponse.failed(sessionId, "Some intents failed: " + finalText)
                : ReactorResponse.completed(sessionId, finalText.toString(), allActions);
    }
}
