package com.intentreactor.tools.dynamic.api;

import com.intentreactor.tools.dynamic.model.ScriptDefinition;

import java.util.List;
import java.util.Optional;

/**
 * SPI for persisting and retrieving {@link com.intentreactor.tools.dynamic.model.ScriptDefinition} records.
 * Implementations: {@link com.intentreactor.tools.dynamic.repository.InMemoryScriptRepository} and
 * {@link com.intentreactor.tools.dynamic.repository.JdbcScriptRepository}.
 */
public interface ScriptRepository {

    Optional<ScriptDefinition> findById(String id);

    Optional<ScriptDefinition> findByName(String name);

    List<ScriptDefinition> findAllActive();

    List<ScriptDefinition> findSimilar(String description);

    void save(ScriptDefinition definition);

    void archive(String id);
}
