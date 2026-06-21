CREATE TABLE IF NOT EXISTS intent_reactor_scripts
(
    id
    VARCHAR
(
    255
) NOT NULL PRIMARY KEY,
    name VARCHAR
(
    255
) NOT NULL,
    version VARCHAR
(
    50
) NOT NULL,
    code TEXT NOT NULL,
    description TEXT,
    parameter_schema TEXT,
    tags TEXT,
    status VARCHAR
(
    50
) NOT NULL DEFAULT 'ACTIVE',
    risky BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_scripts_name_status ON intent_reactor_scripts (name, status);
CREATE INDEX IF NOT EXISTS idx_scripts_status ON intent_reactor_scripts (status);
