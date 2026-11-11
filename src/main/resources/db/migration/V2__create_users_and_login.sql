-- Comptes et authentification (cahier des charges, section 1).

CREATE TABLE users (
    id                BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255) NULL COMMENT 'Argon2id/bcrypt ; NULL pour un compte uniquement Apple/Google',
    first_name        VARCHAR(100) NOT NULL,
    email_verified_at DATETIME(6)  NULL,
    status            ENUM('ACTIVE', 'DEACTIVATED') NOT NULL DEFAULT 'ACTIVE',
    deactivated_at    DATETIME(6)  NULL,
    deletion_due_at   DATETIME(6)  NULL COMMENT 'Suppression des données personnelles (désactivation + 30 jours)',
    created_at        DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at        DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
);

-- Connexion Apple / Google : un compte peut avoir plusieurs fournisseurs rattachés.
CREATE TABLE user_identities (
    id               BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    user_id          BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    provider         ENUM('APPLE', 'GOOGLE') NOT NULL,
    provider_subject VARCHAR(255) NOT NULL COMMENT 'Claim "sub" du jeton d''identité',
    created_at       DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identities_provider_subject (provider, provider_subject),
    KEY idx_user_identities_user (user_id),
    CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Sessions : refresh tokens à rotation. Une famille de jetons = une session ;
-- la réutilisation d'un jeton déjà remplacé révoque toute la famille.
CREATE TABLE refresh_tokens (
    id             BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    user_id        BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id      BINARY(16)  NOT NULL,
    token_hash     CHAR(64)    CHARACTER SET ascii NOT NULL,
    expires_at     DATETIME(6) NOT NULL,
    revoked_at     DATETIME(6) NULL,
    replaced_by_id BINARY(16)  NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id),
    KEY idx_refresh_tokens_family (family_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL
);

-- Liens à usage unique : vérification d'email (24 h) et mot de passe oublié (30 min).
CREATE TABLE one_time_tokens (
    id         BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    user_id    BINARY(16)  NOT NULL,
    type       ENUM('EMAIL_VERIFICATION', 'PASSWORD_RESET') NOT NULL,
    token_hash CHAR(64)    CHARACTER SET ascii NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_one_time_tokens_hash (token_hash),
    KEY idx_one_time_tokens_user_type (user_id, type),
    CONSTRAINT fk_one_time_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
