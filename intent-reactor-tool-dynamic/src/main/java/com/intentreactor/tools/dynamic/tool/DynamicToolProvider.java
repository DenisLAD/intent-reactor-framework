package com.intentreactor.tools.dynamic.tool;

import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.tools.dynamic.api.ScriptRepository;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import com.intentreactor.tools.dynamic.sandbox.RhinoSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ToolProvider} that merges static {@link Tool} beans with dynamically loaded
 * {@link ScriptToolWrapper} instances from the {@link ScriptRepository}.
 * Dynamic tools are lazily cached and invalidated via {@link #invalidateCache()} whenever
 * the repository changes (triggered by {@link com.intentreactor.tools.dynamic.repository.InvalidationAwareScriptRepository}).
 */
public class DynamicToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolProvider.class);

    private final List<Tool> staticTools;
    private final ScriptRepository scriptRepository;
    private final RhinoSandbox rhinoSandbox;
    private final AtomicBoolean cacheValid = new AtomicBoolean(false);
    private volatile List<Tool> dynamicToolsCache = List.of();

    public DynamicToolProvider(List<Tool> staticTools,
                               ScriptRepository scriptRepository,
                               RhinoSandbox rhinoSandbox) {
        this.staticTools = List.copyOf(staticTools);
        this.scriptRepository = scriptRepository;
        this.rhinoSandbox = rhinoSandbox;
        log.info("DynamicToolProvider initialized with {} static tools", staticTools.size());
    }

    @Override
    public List<Tool> getAvailableTools(SessionState sessionState) {
        List<Tool> result = new ArrayList<>(staticTools);
        result.addAll(loadDynamicTools());
        return List.copyOf(result);
    }

    public void invalidateCache() {
        cacheValid.set(false);
        log.debug("Dynamic tools cache invalidated");
    }

    private List<Tool> loadDynamicTools() {
        if (!cacheValid.get()) {
            synchronized (this) {
                if (!cacheValid.get()) {
                    List<ScriptDefinition> active = scriptRepository.findAllActive();
                    List<Tool> loaded = new ArrayList<>();
                    for (ScriptDefinition def : active) {
                        try {
                            loaded.add(new ScriptToolWrapper(def, rhinoSandbox));
                        } catch (Exception e) {
                            log.error("Failed to load dynamic script '{}' ({}): {}",
                                    def.getName(), def.getId(), e.getMessage());
                        }
                    }
                    dynamicToolsCache = List.copyOf(loaded);
                    cacheValid.set(true);
                    log.debug("Loaded {} dynamic tools", loaded.size());
                }
            }
        }
        return dynamicToolsCache;
    }
}
