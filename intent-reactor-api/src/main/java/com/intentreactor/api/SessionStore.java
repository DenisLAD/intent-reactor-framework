package com.intentreactor.api;

import java.util.Optional;

/**
 * Persistence strategy for {@link SessionState} objects.
 *
 * <p>Built-in implementations registered by {@code IntentReactorAutoConfiguration}:
 * <ul>
 *   <li>{@code InMemorySessionStore} — default; sessions are lost on restart</li>
 *   <li>{@code FileSystemSessionStore} — JSON files; {@code intent-reactor.session.store=filesystem}</li>
 *   <li>{@code JdbcSessionStore} — relational DB; {@code intent-reactor.session.store=jdbc}</li>
 *   <li>{@code JpaSessionStore} — JPA entity; {@code intent-reactor.session.store=jpa}</li>
 * </ul>
 *
 * <h2>Implementing custom storage (e.g., Redis)</h2>
 * <pre>{@code
 * @Component
 * @Primary
 * public class RedisSessionStore implements SessionStore {
 *
 *     private final RedisTemplate<String, String> redis;
 *     private final ObjectMapper mapper;
 *
 *     public RedisSessionStore(RedisTemplate<String, String> redis, ObjectMapper mapper) {
 *         this.redis = redis;
 *         this.mapper = mapper;
 *     }
 *
 *     @Override
 *     public Optional<SessionState> findById(String sessionId) {
 *         String json = redis.opsForValue().get("session:" + sessionId);
 *         if (json == null) return Optional.empty();
 *         try {
 *             return Optional.of(mapper.readValue(json, SessionState.class));
 *         } catch (JsonProcessingException e) {
 *             return Optional.empty();
 *         }
 *     }
 *
 *     @Override
 *     public void save(SessionState state) {
 *         state.touch();
 *         try {
 *             redis.opsForValue().set(
 *                 "session:" + state.getId(),
 *                 mapper.writeValueAsString(state),
 *                 Duration.ofHours(24)
 *             );
 *         } catch (JsonProcessingException e) {
 *             throw new RuntimeException("Failed to serialize session", e);
 *         }
 *     }
 *
 *     @Override
 *     public void delete(String sessionId) {
 *         redis.delete("session:" + sessionId);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Note on polymorphic attributes:</strong> {@link SessionState#getAttributes()}
 * may contain complex objects such as {@code MultiIntentContext} or the LATS search tree.
 * When implementing a serialising store, configure Jackson with the appropriate type
 * information or register necessary mixins.
 *
 * @see SessionState
 * @see IntentReactorService
 */
public interface SessionStore {

    /**
     * Looks up a session by its identifier.
     *
     * @param sessionId the session identifier; must not be {@code null}
     * @return an {@code Optional} with the session if found, or empty if it does not exist
     */
    Optional<SessionState> findById(String sessionId);

    /**
     * Persists or updates a session.
     *
     * <p>Implementations should call {@link SessionState#touch()} to update the
     * {@code updatedAt} timestamp before writing, or rely on the framework having done so.
     *
     * @param sessionState the session to persist; must not be {@code null}
     */
    void save(SessionState sessionState);

    /**
     * Removes a session from the store.
     *
     * <p>Must complete without throwing an exception if no session with the given ID exists.
     *
     * @param sessionId the identifier of the session to remove; must not be {@code null}
     */
    void delete(String sessionId);
}
