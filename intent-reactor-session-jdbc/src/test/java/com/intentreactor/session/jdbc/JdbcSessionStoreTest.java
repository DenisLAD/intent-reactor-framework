package com.intentreactor.session.jdbc;

import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@TestPropertySource(properties = "intent-reactor.session.store=jdbc")
class JdbcSessionStoreTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcSessionStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS intent_reactor_sessions (
                    id VARCHAR(255) PRIMARY KEY,
                    state TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
        store = new JdbcSessionStore(jdbcTemplate);
    }

    @Test
    void saveAndFind() {
        SessionState session = new SessionState("j1");
        session.addMessage(Message.user("hello"));
        store.save(session);

        Optional<SessionState> found = store.findById("j1");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("j1");
        assertThat(found.get().getMessages()).hasSize(1);
    }

    @Test
    void findMissingReturnsEmpty() {
        assertThat(store.findById("nope")).isEmpty();
    }

    @Test
    void upsertUpdatesExisting() {
        SessionState session = new SessionState("j2");
        store.save(session);

        session.addMessage(Message.user("updated"));
        store.save(session);

        Optional<SessionState> found = store.findById("j2");
        assertThat(found.get().getMessages()).hasSize(1);
    }

    @Test
    void delete() {
        store.save(new SessionState("j3"));
        store.delete("j3");
        assertThat(store.findById("j3")).isEmpty();
    }
}
