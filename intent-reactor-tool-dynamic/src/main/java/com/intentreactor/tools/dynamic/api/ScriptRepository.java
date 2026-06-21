package com.intentreactor.tools.dynamic.api;

import com.intentreactor.tools.dynamic.model.ScriptDefinition;

import java.util.List;
import java.util.Optional;

public interface ScriptRepository {

    Optional<ScriptDefinition> findById(String id);

    Optional<ScriptDefinition> findByName(String name);

    List<ScriptDefinition> findAllActive();

    List<ScriptDefinition> findSimilar(String description);

    void save(ScriptDefinition definition);

    void archive(String id);
}
