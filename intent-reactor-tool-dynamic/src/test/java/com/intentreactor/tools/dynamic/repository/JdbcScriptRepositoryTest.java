package com.intentreactor.tools.dynamic.repository;

import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@TestPropertySource(properties = "intent-reactor.tools.dynamic-scripting.enabled=true")
class JdbcScriptRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcScriptRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS intent_reactor_scripts (
                    id VARCHAR(255) NOT NULL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    version VARCHAR(50) NOT NULL,
                    code TEXT NOT NULL,
                    description TEXT,
                    parameter_schema TEXT,
                    tags TEXT,
                    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                    risky BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
        repository = new JdbcScriptRepository();
        ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void saveAndFindById() {
        ScriptDefinition def = new ScriptDefinition("id-1", "my_tool", "v1",
                "test description", "function execute(input){return 1;}", null);
        repository.save(def);

        Optional<ScriptDefinition> found = repository.findById("id-1");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("my_tool");
        assertThat(found.get().getCode()).contains("return 1");
    }

    @Test
    void findMissing_returnsEmpty() {
        assertThat(repository.findById("nonexistent")).isEmpty();
    }

    @Test
    void saveUpsert_updatesExisting() {
        ScriptDefinition def = new ScriptDefinition("id-1", "tool", "v1",
                "desc", "function execute(input){return 1;}", null);
        repository.save(def);
        def.setCode("function execute(input){return 2;}");
        repository.save(def);

        assertThat(repository.findById("id-1").get().getCode()).contains("return 2");
    }

    @Test
    void archive_changesStatus() {
        repository.save(new ScriptDefinition("id-1", "tool", "v1",
                "desc", "function execute(input){}", null));
        repository.archive("id-1");

        List<ScriptDefinition> active = repository.findAllActive();
        assertThat(active).isEmpty();
    }

    @Test
    void findSimilar_matchesDescription() {
        repository.save(new ScriptDefinition("id-1", "csv_tool", "v1",
                "parses CSV files", "function execute(input){}", null));
        repository.save(new ScriptDefinition("id-2", "json_tool", "v1",
                "parses JSON data", "function execute(input){}", null));

        List<ScriptDefinition> results = repository.findSimilar("csv");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("csv_tool");
    }
}
