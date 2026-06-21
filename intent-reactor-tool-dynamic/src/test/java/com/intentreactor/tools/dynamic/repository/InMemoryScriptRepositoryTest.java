package com.intentreactor.tools.dynamic.repository;

import com.intentreactor.tools.dynamic.api.ScriptStatus;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryScriptRepositoryTest {

    private InMemoryScriptRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryScriptRepository();
    }

    @Test
    void saveAndFindById() {
        ScriptDefinition def = new ScriptDefinition("id-1", "my_tool", "v1",
                "test desc", "function execute(input){return 1;}", null);
        repository.save(def);

        Optional<ScriptDefinition> found = repository.findById("id-1");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("my_tool");
    }

    @Test
    void findMissing_returnsEmpty() {
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    void findByName_returnsActiveScript() {
        repository.save(new ScriptDefinition("id-1", "my_tool", "v1",
                "desc", "function execute(input){}", null));
        assertThat(repository.findByName("my_tool")).isPresent();
    }

    @Test
    void archive_hidesFromFindAllActive() {
        repository.save(new ScriptDefinition("id-1", "my_tool", "v1",
                "desc", "function execute(input){}", null));
        repository.archive("id-1");

        assertThat(repository.findAllActive()).isEmpty();
        assertThat(repository.findById("id-1").get().getStatus()).isEqualTo(ScriptStatus.ARCHIVED);
    }

    @Test
    void findSimilar_matchesDescription() {
        repository.save(new ScriptDefinition("id-1", "csv_parser", "v1",
                "parses CSV data with comma separator", "function execute(input){}", null));
        repository.save(new ScriptDefinition("id-2", "json_parser", "v1",
                "parses JSON objects", "function execute(input){}", null));

        List<ScriptDefinition> results = repository.findSimilar("CSV");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("csv_parser");
    }
}
