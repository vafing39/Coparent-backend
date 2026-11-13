-- Répétition personnalisée et historique des modifications (cahier des charges, 5).

-- Personnalisé : tous les N jours, semaines (jours choisis) ou mois (même quantième, ou même rang du jour).
ALTER TABLE events
    MODIFY COLUMN repeat_frequency ENUM('DAILY', 'WEEKLY', 'BIWEEKLY', 'MONTHLY', 'CUSTOM') NULL
        COMMENT 'NULL : événement unique',
    ADD COLUMN repeat_interval   TINYINT NULL COMMENT 'CUSTOM : tous les N (1 à 12)' AFTER repeat_frequency,
    ADD COLUMN repeat_unit       ENUM('DAY', 'WEEK', 'MONTH') NULL COMMENT 'CUSTOM : unité de N' AFTER repeat_interval,
    ADD COLUMN repeat_month_mode ENUM('DAY_OF_MONTH', 'WEEKDAY_OF_MONTH') NULL
        COMMENT 'CUSTOM par mois : le 19, ou le troisième jeudi' AFTER repeat_unit,
    ADD COLUMN series_id         BINARY(16) NULL
        COMMENT 'Regroupe une série et ce qui en est détaché (historique)' AFTER family_id;

UPDATE events SET series_id = id;

ALTER TABLE events
    MODIFY COLUMN series_id BINARY(16) NOT NULL COMMENT 'Regroupe une série et ce qui en est détaché (historique)',
    ADD KEY idx_events_family_series (family_id, series_id);

-- Jours d'une répétition personnalisée par semaine. Horaires NULL : celui de l'événement.
CREATE TABLE event_repeat_days (
    event_id    BINARY(16) NOT NULL,
    day_of_week TINYINT    NOT NULL COMMENT '1 lundi … 7 dimanche',
    start_time  TIME       NULL,
    end_time    TIME       NULL,
    PRIMARY KEY (event_id, day_of_week),
    CONSTRAINT fk_event_repeat_days_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
);

-- Historique des modifications, visible par les parents qui voient l'événement. Sans clé vers events :
-- il survit aux séries arrêtées, détachées ou supprimées.
CREATE TABLE event_changes (
    id              BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id       BINARY(16)   NOT NULL,
    series_id       BINARY(16)   NOT NULL,
    author_id       BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    action          ENUM('CREATED', 'UPDATED', 'DELETED') NOT NULL,
    scope           ENUM('ONE', 'FOLLOWING', 'ALL') NULL COMMENT 'Séances concernées (série seulement)',
    occurrence_date DATE         NULL COMMENT 'Séance ouverte (ONE, FOLLOWING)',
    fields          VARCHAR(200) NULL COMMENT 'UPDATED : champs modifiés, séparés par des virgules',
    created_at      DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_event_changes_series (series_id, created_at),
    CONSTRAINT fk_event_changes_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_changes_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL
);

INSERT INTO event_changes (family_id, series_id, author_id, action, created_at)
SELECT family_id, series_id, author_id, 'CREATED', created_at FROM events;
