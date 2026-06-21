package com.intentreactor.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FileSystemSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemSessionStore.class);

    private final Path basePath;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public FileSystemSessionStore(IntentReactorProperties properties, ObjectMapper mapper) {
        String pathStr = properties.getSession().getFilesystem().getPath();
        this.basePath = Paths.get(pathStr);
        this.mapper = mapper;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create session store directory: " + pathStr, e);
        }
    }

    @Override
    public Optional<SessionState> findById(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File file = sessionFile(sessionId);
            if (!file.exists()) return Optional.empty();
            try {
                return Optional.of(mapper.readValue(file, SessionState.class));
            } catch (IOException e) {
                log.error("Failed to read session {}", sessionId, e);
                return Optional.empty();
            }
        }
    }

    @Override
    public void save(SessionState sessionState) {
        sessionState.touch();
        synchronized (lockFor(sessionState.getId())) {
            try {
                Path tmp = basePath.resolve(sessionState.getId() + ".json.tmp");
                Path dest = basePath.resolve(sessionState.getId() + ".json");
                mapper.writeValue(tmp.toFile(), sessionState);
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    log.warn("Atomic move not supported, falling back to regular move: {}", ex.getMessage());
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.error("Failed to save session {}", sessionState.getId(), e);
            }
        }
    }

    @Override
    public void delete(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File file = sessionFile(sessionId);
            if (file.exists() && !file.delete()) {
                log.warn("Could not delete session file for {}", sessionId);
            }
        }
    }

    private Object lockFor(String id) {
        return sessionLocks.computeIfAbsent(id, k -> new Object());
    }

    private File sessionFile(String sessionId) {
        return basePath.resolve(sessionId + ".json").toFile();
    }
}
