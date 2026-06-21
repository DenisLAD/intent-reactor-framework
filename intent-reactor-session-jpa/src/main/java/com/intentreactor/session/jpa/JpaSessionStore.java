package com.intentreactor.session.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "intent-reactor.session", name = "store", havingValue = "jpa")
@ConditionalOnClass(JpaRepository.class)
public class JpaSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionStore.class);

    private final SessionEntityRepository repository;
    private final ObjectMapper objectMapper;

    public JpaSessionStore(SessionEntityRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<SessionState> findById(String sessionId) {
        return repository.findById(sessionId).flatMap(entity -> {
            try {
                return Optional.of(objectMapper.readValue(entity.getState(), SessionState.class));
            } catch (Exception e) {
                log.error("Failed to deserialize session {}", sessionId, e);
                return Optional.empty();
            }
        });
    }

    @Override
    public void save(SessionState sessionState) {
        sessionState.touch();
        try {
            String json = objectMapper.writeValueAsString(sessionState);
            SessionEntity entity = repository.findById(sessionState.getId())
                    .orElseGet(() -> {
                        SessionEntity e = new SessionEntity();
                        e.setId(sessionState.getId());
                        e.setCreatedAt(sessionState.getCreatedAt());
                        return e;
                    });
            entity.setState(json);
            entity.setUpdatedAt(sessionState.getUpdatedAt());
            repository.save(entity);
        } catch (Exception e) {
            log.error("Failed to save session {}", sessionState.getId(), e);
        }
    }

    @Override
    public void delete(String sessionId) {
        repository.deleteById(sessionId);
    }
}
