package com.intentreactor.session.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link SessionEntity}, keyed by session ID.
 */
public interface SessionEntityRepository extends JpaRepository<SessionEntity, String> {
}
