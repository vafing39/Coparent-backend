-- Sac à préparer : une liste durable par enfant ; une case cochée vaut pour un passage de garde.
CREATE TABLE bag_items (
    id         BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id  BINARY(16)   NOT NULL,
    child_id   BINARY(16)   NOT NULL,
    label      VARCHAR(100) NOT NULL,
    created_by BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    created_at DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_bag_items_family (family_id),
    KEY idx_bag_items_child (child_id),
    CONSTRAINT fk_bag_items_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_bag_items_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_bag_items_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE bag_checks (
    id          BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    item_id     BINARY(16)  NOT NULL,
    handover_at DATETIME(6) NOT NULL COMMENT 'Instant UTC du passage de garde concerné',
    checked_by  BINARY(16)  NULL COMMENT 'NULL si le compte a été supprimé',
    checked_at  DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bag_checks_item_handover (item_id, handover_at),
    CONSTRAINT fk_bag_checks_item FOREIGN KEY (item_id) REFERENCES bag_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_bag_checks_checked_by FOREIGN KEY (checked_by) REFERENCES users (id) ON DELETE SET NULL
);
