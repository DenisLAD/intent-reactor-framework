package com.intentreactor.tools.dynamic.repository;

import com.intentreactor.tools.dynamic.api.ScriptRepository;
import com.intentreactor.tools.dynamic.api.ScriptStatus;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link ScriptRepository} backed by a {@link java.util.concurrent.ConcurrentHashMap}.
 * Scripts are lost on application restart.
 */
public class InMemoryScriptRepository implements ScriptRepository {

    private final ConcurrentHashMap<String, ScriptDefinition> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ScriptDefinition> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<ScriptDefinition> findByName(String name) {
        return store.values().stream()
                .filter(s -> s.getName().equals(name) && s.getStatus() == ScriptStatus.ACTIVE)
                .max(Comparator.comparing(ScriptDefinition::getVersion));
    }

    @Override
    public List<ScriptDefinition> findAllActive() {
        return store.values().stream()
                .filter(s -> s.getStatus() == ScriptStatus.ACTIVE)
                .sorted(Comparator.comparing(ScriptDefinition::getName))
                .collect(Collectors.toList());
    }

    @Override
    public List<ScriptDefinition> findSimilar(String description) {
        if (description == null || description.isBlank()) return List.of();
        String lower = description.toLowerCase();
        return store.values().stream()
                .filter(s -> s.getStatus() == ScriptStatus.ACTIVE)
                .filter(s -> s.getDescription() != null
                        && s.getDescription().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    @Override
    public void save(ScriptDefinition definition) {
        definition.setUpdatedAt(LocalDateTime.now());
        store.put(definition.getId(), definition);
    }

    @Override
    public void archive(String id) {
        ScriptDefinition def = store.get(id);
        if (def != null) {
            def.setStatus(ScriptStatus.ARCHIVED);
            def.setUpdatedAt(LocalDateTime.now());
        }
    }
}
