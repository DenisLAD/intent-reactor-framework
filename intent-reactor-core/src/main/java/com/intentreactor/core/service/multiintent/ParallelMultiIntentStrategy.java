package com.intentreactor.core.service.multiintent;

import com.intentreactor.api.Intent;
import com.intentreactor.api.MultiIntentContext;
import com.intentreactor.api.MultiIntentStrategy;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionAttributeKeys;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SingleIntentExecutor;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ParallelMultiIntentStrategy implements MultiIntentStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParallelMultiIntentStrategy.class);

    private final ExecutorService executorService;
    private final IntentReactorProperties properties;

    public ParallelMultiIntentStrategy(ExecutorService executorService,
                                       IntentReactorProperties properties) {
        this.executorService = executorService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "parallel";
    }

    @Override
    public ReactorResponse execute(SessionState session, MultiIntentContext ctx,
                                   boolean persistent, SingleIntentExecutor executor) {
        List<Intent> pending = new ArrayList<>(ctx.getPendingIntents());
        ctx.getPendingIntents().clear();

        // Keep intentName paired with its future so failures can be recorded by name.
        List<Map.Entry<String, CompletableFuture<Map.Entry<String, ReactorResponse>>>> namedFutures =
                pending.stream()
                        .map(intentItem -> {
                            CompletableFuture<Map.Entry<String, ReactorResponse>> f =
                                    CompletableFuture.supplyAsync(() -> {
                                        SessionState isolated = cloneSession(session, intentItem.getName());
                                        isolated.setPlanState(new PlanState(intentItem.getName()));
                                        ReactorResponse r = executor.execute(isolated, false);
                                        return Map.entry(intentItem.getName(), r);
                                    }, executorService);
                            return Map.entry(intentItem.getName(), f);
                        })
                        .toList();

        for (Map.Entry<String, CompletableFuture<Map.Entry<String, ReactorResponse>>> nf : namedFutures) {
            String intentName = nf.getKey();
            CompletableFuture<Map.Entry<String, ReactorResponse>> f = nf.getValue();
            try {
                Map.Entry<String, ReactorResponse> entry = f.get(
                        (long) properties.getPlanning().getParallelTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
                ctx.getResults().put(entry.getKey(), entry.getValue());
            } catch (TimeoutException e) {
                log.warn("Parallel intent '{}' timed out", intentName);
                f.cancel(true);
                ctx.getResults().put(intentName, ReactorResponse.failed(session.getId(), "Parallel execution timed out"));
            } catch (InterruptedException | ExecutionException e) {
                log.error("Parallel intent '{}' failed", intentName, e);
                ctx.getResults().put(intentName, ReactorResponse.failed(session.getId(),
                        "Parallel execution failed: " + e.getMessage()));
            }
        }

        return SequentialMultiIntentStrategy.mergeResults(session.getId(), ctx);
    }

    private static SessionState cloneSession(SessionState original, String suffix) {
        SessionState clone = new SessionState(
                original.getId() + "-parallel-" + suffix.replaceAll("[^a-zA-Z0-9]", ""));
        clone.setMessages(new ArrayList<>(original.getMessages()));
        Map<String, Object> clonedAttrs = new HashMap<>(original.getAttributes());
        clonedAttrs.remove(SessionAttributeKeys.MULTI_INTENT_STATE_KEY);
        clone.setAttributes(clonedAttrs);
        return clone;
    }
}
