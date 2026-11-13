-- Événements répétés (cahier des charges, 5). La règle est portée par l'événement lui-même : sa date est la
-- première séance, ses heures valent pour chaque séance (heure de Paris). Les séances sont calculées à la
-- lecture, pour suivre le calendrier de garde (filtre par parent) et les vacances scolaires.
ALTER TABLE events
    ADD COLUMN repeat_frequency     ENUM('DAILY', 'WEEKLY', 'BIWEEKLY', 'MONTHLY') NULL
        COMMENT 'NULL : événement unique' AFTER visibility,
    ADD COLUMN repeat_only_with     ENUM('PARENT_A', 'PARENT_B') NULL
        COMMENT 'Seulement quand les enfants sont chez ce parent (nuit du jour de la séance)' AFTER repeat_frequency,
    ADD COLUMN repeat_skip_holidays BOOLEAN NULL COMMENT 'Pas pendant les vacances scolaires' AFTER repeat_only_with,
    ADD COLUMN repeat_end           ENUM('NEVER', 'SCHOOL_YEAR', 'COUNT') NULL AFTER repeat_skip_holidays,
    ADD COLUMN repeat_count         SMALLINT NULL COMMENT 'Fin COUNT : nombre de séances' AFTER repeat_end,
    ADD COLUMN repeat_until         DATE NULL
        COMMENT 'Dernier jour possible (fin d''année scolaire, ou série arrêtée à une modification)' AFTER repeat_count;

-- Séances retirées d'une série (supprimées seules, ou remplacées par un événement unique modifié).
CREATE TABLE event_exceptions (
    event_id        BINARY(16) NOT NULL,
    occurrence_date DATE       NOT NULL,
    PRIMARY KEY (event_id, occurrence_date),
    CONSTRAINT fk_event_exceptions_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
);
