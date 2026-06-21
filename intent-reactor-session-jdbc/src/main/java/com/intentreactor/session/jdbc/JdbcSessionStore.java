package com.intentreactor.session.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "intent-reactor.session", name = "store", havingValue = "jdbc")
@ConditionalOnClass(JdbcTemplate.class)
public class JdbcSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public JdbcSessionStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "intent_reactor_sessions");
    }

    public JdbcSessionStore(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<SessionState> findById(String sessionId) {
        String sql = "SELECT state FROM " + tableName + " WHERE id = ?";
        try {
            String json = jdbcTemplate.queryForObject(sql, String.class, sessionId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, SessionState.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to load session {}", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(SessionState sessionState) {
        sessionState.touch();
        try {
            String json = objectMapper.writeValueAsString(sessionState);
            String updateSql = "UPDATE " + tableName
                    + " SET state = ?, updated_at = ? WHERE id = ?";
            int updated = jdbcTemplate.update(updateSql,
                    json,
                    sessionState.getUpdatedAt(),
                    sessionState.getId());
            if (updated == 0) {
                String insertSql = "INSERT INTO " + tableName
                        + " (id, state, created_at, updated_at) VALUES (?, ?, ?, ?)";
                jdbcTemplate.update(insertSql,
                        sessionState.getId(),
                        json,
                        sessionState.getCreatedAt(),
                        sessionState.getUpdatedAt());
            }
        } catch (Exception e) {
            log.error("Failed to save session {}", sessionState.getId(), e);
        }
    }

    @Override
    public void delete(String sessionId) {
        jdbcTemplate.update("DELETE FROM " + tableName + " WHERE id = ?", sessionId);
    }
}
