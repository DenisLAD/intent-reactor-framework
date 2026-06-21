package com.intentreactor.core.session;

import com.intentreactor.api.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySessionStoreTest {

    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
    }

    @Test
    void saveAndFind() {
        SessionState session = new SessionState("s1");
        store.save(session);

        Optional<SessionState> found = store.findById("s1");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("s1");
    }

    @Test
    void findMissingReturnsEmpty() {
        assertThat(store.findById("nonexistent")).isEmpty();
    }

    @Test
    void delete() {
        store.save(new SessionState("s2"));
        store.delete("s2");
        assertThat(store.findById("s2")).isEmpty();
    }

    @Test
    void saveUpdatesTimestamp() throws InterruptedException {
        SessionState session = new SessionState("s3");
        var before = session.getUpdatedAt();
        Thread.sleep(5);
        store.save(session);
        assertThat(session.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}
