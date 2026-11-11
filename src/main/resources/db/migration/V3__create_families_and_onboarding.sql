-- Première connexion : famille, enfants, rythme de garde, invitation de l'autre parent
-- (cahier des charges, sections 2, 3 et 4).
-- Instants en DATETIME(6) UTC. DATE et TIME servent aux dates et heures locales
-- (naissance, bascule de garde à l'heure de Paris) : ce ne sont pas des instants.

-- Étape 2 : la famille.
CREATE TABLE families (
    id            BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    name          VARCHAR(100) NOT NULL,
    vacation_zone ENUM('A', 'B', 'C', 'OTHER') NOT NULL COMMENT 'Zone de vacances scolaires (OTHER : Corse, outre-mer, étranger)',
    created_by    BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé : la famille reste à l''autre parent',
    created_at    DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at    DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_families_created_by (created_by),
    CONSTRAINT fk_families_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Membres : au plus deux parents (un A, un B). Famille élargie et professionnels en priorité 2.
CREATE TABLE family_members (
    id        BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id BINARY(16)  NOT NULL,
    user_id   BINARY(16)  NOT NULL,
    role      ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_family_members_role (family_id, role),
    UNIQUE KEY uk_family_members_user (family_id, user_id),
    KEY idx_family_members_user (user_id),
    CONSTRAINT fk_family_members_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_family_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Étape 3 : les enfants. La fiche santé (allergies, médecin) viendra en priorité 2.
CREATE TABLE children (
    id         BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id  BINARY(16)   NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    birth_date DATE         NOT NULL COMMENT 'Date calendaire, sans fuseau',
    created_at DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_children_family (family_id),
    CONSTRAINT fk_children_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE
);

-- Étape 4 : le rythme de garde et les règles de vacances. Une ligne par version :
-- une modification est proposée sans écraser le rythme en vigueur (accord des deux parents).
CREATE TABLE custody_schedules (
    id                            BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id                     BINARY(16)  NOT NULL,
    pattern                       ENUM('ALTERNATE_WEEKS', 'TWO_TWO_THREE', 'TWO_TWO_FIVE_FIVE',
                                       'ALTERNATE_WEEKENDS_WEDNESDAY', 'CUSTOM') NOT NULL,
    custom_sequence               VARCHAR(56) CHARACTER SET ascii NULL COMMENT 'CUSTOM : une lettre par nuit (A ou B), 8 semaines au plus',
    start_date                    DATE        NOT NULL COMMENT 'Première bascule ; son jour de semaine est le jour de bascule',
    handover_time                 TIME        NOT NULL COMMENT 'Heure de bascule à l''heure de Paris (pas UTC)',
    starting_parent               ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    holiday_first_half_even_years ENUM('PARENT_A', 'PARENT_B') NULL COMMENT 'Petites vacances : 1re moitié les années paires (ordre inversé les années impaires)',
    summer_split                  ENUM('FORTNIGHTS', 'MONTHS') NULL COMMENT 'Été par quinzaines ou par mois',
    status                        ENUM('PROPOSED', 'ACTIVE', 'REPLACED', 'REJECTED') NOT NULL DEFAULT 'PROPOSED',
    proposed_by                   BINARY(16)  NULL,
    decided_by                    BINARY(16)  NULL COMMENT 'Parent qui a validé ou refusé',
    decided_at                    DATETIME(6) NULL,
    created_at                    DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    -- Index unique partiel : au plus un rythme ACTIVE par famille.
    active_family_id              BINARY(16) AS (IF(status = 'ACTIVE', family_id, NULL)) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_custody_schedules_active (active_family_id),
    KEY idx_custody_schedules_family (family_id),
    KEY idx_custody_schedules_proposed_by (proposed_by),
    KEY idx_custody_schedules_decided_by (decided_by),
    CONSTRAINT ck_custody_schedules_custom CHECK (
        (pattern = 'CUSTOM' AND custom_sequence IS NOT NULL AND custom_sequence REGEXP '^[AB]+$')
        OR (pattern <> 'CUSTOM' AND custom_sequence IS NULL)
    ),
    CONSTRAINT fk_custody_schedules_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_custody_schedules_proposed_by FOREIGN KEY (proposed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_custody_schedules_decided_by FOREIGN KEY (decided_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Étape 5 : invitation de l'autre parent. Lien à usage unique, 7 jours ;
-- un renouvellement révoque le précédent. Seul le SHA-256 du jeton est stocké.
CREATE TABLE family_invitations (
    id                 BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id          BINARY(16)   NOT NULL,
    invited_by         BINARY(16)   NULL,
    invitee_first_name VARCHAR(100) NOT NULL,
    token_hash         CHAR(64)     CHARACTER SET ascii NOT NULL,
    expires_at         DATETIME(6)  NOT NULL,
    accepted_at        DATETIME(6)  NULL,
    accepted_by        BINARY(16)   NULL,
    revoked_at         DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_family_invitations_hash (token_hash),
    KEY idx_family_invitations_family (family_id),
    KEY idx_family_invitations_invited_by (invited_by),
    KEY idx_family_invitations_accepted_by (accepted_by),
    CONSTRAINT fk_family_invitations_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_family_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_family_invitations_accepted_by FOREIGN KEY (accepted_by) REFERENCES users (id) ON DELETE SET NULL
);
