CREATE TABLE IF NOT EXISTS intent_reactor_sessions
(
    id
    VARCHAR
(
    255
) NOT NULL PRIMARY KEY,
    state TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
    );
