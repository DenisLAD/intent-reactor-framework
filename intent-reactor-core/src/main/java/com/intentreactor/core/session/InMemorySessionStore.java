package com.intentreactor.core.session;

import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link SessionStore} backed by a {@link java.util.concurrent.ConcurrentHashMap}.
 * Sessions are lost on application restart.
 */
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, SessionState> store = new ConcurrentHashMap<>();

    @Override
    public Optional<SessionState> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public void save(SessionState sessionState) {
        sessionState.touch();
        store.put(sessionState.getId(), sessionState);
    }

    @Override
    public void delete(String sessionId) {
        store.remove(sessionId);
    }
}
