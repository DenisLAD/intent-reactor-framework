package com.intentreactor.tools.dynamic.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intentreactor.tools.dynamic.api.ScriptRepository;
import com.intentreactor.tools.dynamic.api.ScriptStatus;
import com.intentreactor.tools.dynamic.model.ScriptDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JdbcScriptRepository implements ScriptRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcScriptRepository.class);
    private static final String TABLE = "intent_reactor_scripts";
    private final ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    public JdbcScriptRepository() {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<ScriptDefinition> findById(String id) {
        String sql = "SELECT * FROM " + TABLE + " WHERE id = ?";
        try {
            List<ScriptDefinition> results = jdbcTemplate.query(sql, new ScriptRowMapper(), id);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.error("findById failed for id={}", id, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<ScriptDefinition> findByName(String name) {
        String sql = "SELECT * FROM " + TABLE
                + " WHERE name = ? AND status = 'ACTIVE'"
                + " ORDER BY updated_at DESC LIMIT 1";
        try {
            List<ScriptDefinition> results = jdbcTemplate.query(sql, new ScriptRowMapper(), name);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.error("findByName failed for name={}", name, e);
            return Optional.empty();
        }
    }

    @Override
    public List<ScriptDefinition> findAllActive() {
        String sql = "SELECT * FROM " + TABLE + " WHERE status = 'ACTIVE' ORDER BY name";
        try {
            return jdbcTemplate.query(sql, new ScriptRowMapper());
        } catch (Exception e) {
            log.error("findAllActive failed", e);
            return List.of();
        }
    }

    @Override
    public List<ScriptDefinition> findSimilar(String description) {
        if (description == null || description.isBlank()) return List.of();
        String sql = "SELECT * FROM " + TABLE
                + " WHERE status = 'ACTIVE' AND LOWER(description) LIKE ? ORDER BY name";
        try {
            return jdbcTemplate.query(sql, new ScriptRowMapper(),
                    "%" + description.toLowerCase() + "%");
        } catch (Exception e) {
            log.error("findSimilar failed", e);
            return List.of();
        }
    }

    @Override
    public void save(ScriptDefinition definition) {
        definition.setUpdatedAt(LocalDateTime.now());
        if (definition.getCreatedAt() == null) definition.setCreatedAt(LocalDateTime.now());
        try {
            String schemaJson = definition.getParameterSchema() != null
                    ? objectMapper.writeValueAsString(definition.getParameterSchema()) : null;
            String tagsJson = definition.getTags() != null
                    ? objectMapper.writeValueAsString(definition.getTags()) : null;

            String status = definition.getStatus() != null
                    ? definition.getStatus().name() : ScriptStatus.ACTIVE.name();
            String updateSql = "UPDATE " + TABLE
                    + " SET name=?, version=?, code=?, description=?, parameter_schema=?,"
                    + " tags=?, status=?, risky=?, updated_at=? WHERE id=?";
            int updated = jdbcTemplate.update(updateSql,
                    definition.getName(), definition.getVersion(),
                    definition.getCode(), definition.getDescription(),
                    schemaJson, tagsJson, status,
                    definition.isRisky(), definition.getUpdatedAt(), definition.getId());
            if (updated == 0) {
                String insertSql = "INSERT INTO " + TABLE
                        + " (id, name, version, code, description, parameter_schema, tags, status, risky, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                jdbcTemplate.update(insertSql,
                        definition.getId(), definition.getName(), definition.getVersion(),
                        definition.getCode(), definition.getDescription(),
                        schemaJson, tagsJson, status,
                        definition.isRisky(), definition.getCreatedAt(), definition.getUpdatedAt());
            }
        } catch (Exception e) {
            log.error("save failed for script id={}", definition.getId(), e);
        }
    }

    @Override
    public void archive(String id) {
        String sql = "UPDATE " + TABLE + " SET status = 'ARCHIVED', updated_at = ? WHERE id = ?";
        try {
            jdbcTemplate.update(sql, LocalDateTime.now(), id);
        } catch (Exception e) {
            log.error("archive failed for id={}", id, e);
        }
    }

    @SuppressWarnings("unchecked")
    private class ScriptRowMapper implements RowMapper<ScriptDefinition> {
        @Override
        public ScriptDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            ScriptDefinition def = new ScriptDefinition();
            def.setId(rs.getString("id"));
            def.setName(rs.getString("name"));
            def.setVersion(rs.getString("version"));
            def.setCode(rs.getString("code"));
            def.setDescription(rs.getString("description"));
            def.setStatus(ScriptStatus.valueOf(rs.getString("status")));
            def.setRisky(rs.getBoolean("risky"));
            def.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
            def.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
            try {
                String schemaJson = rs.getString("parameter_schema");
                if (schemaJson != null) def.setParameterSchema(objectMapper.readValue(schemaJson, Map.class));
                String tagsJson = rs.getString("tags");
                if (tagsJson != null) def.setTags(objectMapper.readValue(tagsJson, List.class));
            } catch (Exception e) {
                log.warn("Failed to parse JSON columns for script {}", def.getId(), e);
            }
            return def;
        }
    }
}
