package com.intentreactor.tools.dynamic.repository;

import com.intentreactor.tools.dynamic.api.ScriptRepository;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import com.intentreactor.tools.dynamic.tool.DynamicToolProvider;

import java.util.List;
import java.util.Optional;

public class InvalidationAwareScriptRepository implements ScriptRepository {

    private final ScriptRepository delegate;
    private DynamicToolProvider provider;

    public InvalidationAwareScriptRepository(ScriptRepository delegate) {
        this.delegate = delegate;
    }

    public void setProvider(DynamicToolProvider provider) {
        this.provider = provider;
    }

    @Override
    public void save(ScriptDefinition definition) {
        delegate.save(definition);
        if (provider != null) provider.invalidateCache();
    }

    @Override
    public void archive(String id) {
        delegate.archive(id);
        if (provider != null) provider.invalidateCache();
    }

    @Override
    public Optional<ScriptDefinition> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<ScriptDefinition> findByName(String name) {
        return delegate.findByName(name);
    }

    @Override
    public List<ScriptDefinition> findAllActive() {
        return delegate.findAllActive();
    }

    @Override
    public List<ScriptDefinition> findSimilar(String description) {
        return delegate.findSimilar(description);
    }
}
