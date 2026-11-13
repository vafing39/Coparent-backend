-- Événements de la famille. Un événement privé n'est visible que par son auteur (vérifié par le serveur).
CREATE TABLE events (
    id          BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id   BINARY(16)   NOT NULL,
    author_id   BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    title       VARCHAR(100) NOT NULL,
    type        ENUM('APPOINTMENT','ACTIVITY','SCHOOL','OTHER') NOT NULL,
    all_day     BOOLEAN      NOT NULL,
    starts_at   DATETIME(6)  NOT NULL COMMENT 'Instant UTC (journée entière : minuit à Paris)',
    ends_at     DATETIME(6)  NOT NULL COMMENT 'Instant UTC (journée entière : minuit suivant à Paris)',
    location    VARCHAR(200) NULL,
    reminder    ENUM('NONE','ONE_HOUR','DAY_BEFORE','TWO_DAYS') NOT NULL,
    visibility  ENUM('SHARED','PRIVATE') NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at  DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_events_family_start (family_id, starts_at),
    CONSTRAINT fk_events_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_events_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE event_children (
    event_id BINARY(16) NOT NULL,
    child_id BINARY(16) NOT NULL,
    PRIMARY KEY (event_id, child_id),
    CONSTRAINT fk_event_children_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_children_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE
);
