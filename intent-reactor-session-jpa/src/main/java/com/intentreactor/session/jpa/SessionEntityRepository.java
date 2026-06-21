package com.intentreactor.session.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, String> {
}
