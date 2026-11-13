-- « À traiter » : une ligne par demande qui attend la réponse d'un parent (cahier des charges, 8).
-- assignee_id décide de la section : « À vous de répondre » pour lui, « En attente de … » pour l'autre parent.
CREATE TABLE to_do_items (
    id             BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id      BINARY(16)   NOT NULL,
    kind           ENUM('CUSTODY_SCHEDULE', 'EXCHANGE', 'EXPENSE', 'MEMBER', 'DEVICE', 'PUBLICATION_REMOVAL', 'PENSION') NOT NULL,
    subject        VARCHAR(150) NOT NULL COMMENT 'Ce qui est demandé, ex. « Cantine de novembre · 64,20 € »',
    requested_by   BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    assignee_id    BINARY(16)   NOT NULL COMMENT 'Le parent qui doit répondre',
    requested_at   DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    last_nudged_at DATETIME(6)  NULL COMMENT 'Dernière relance (une par jour au plus)',
    resolved_at    DATETIME(6)  NULL COMMENT 'Réponse donnée : la ligne quitte la liste',
    PRIMARY KEY (id),
    KEY idx_to_do_items_family_open (family_id, resolved_at),
    CONSTRAINT fk_to_do_items_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_to_do_items_requested_by FOREIGN KEY (requested_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_to_do_items_assignee FOREIGN KEY (assignee_id) REFERENCES users (id) ON DELETE CASCADE
);
