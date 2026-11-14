-- Dépenses partagées et règlements entre les parents (priorité 2).

-- Une dépense payée par un parent, partagée selon payer_share_percent (part du payeur). L'autre parent l'accepte
-- ou la conteste ; seules les dépenses validées comptent dans le solde.
CREATE TABLE expenses (
    id                  BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id           BINARY(16)   NOT NULL,
    created_by          BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    author_role         ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    label               VARCHAR(120) NOT NULL,
    amount_cents        INT          NOT NULL,
    category            ENUM('HEALTH', 'SCHOOL', 'CANTEEN', 'ACTIVITIES', 'CLOTHING', 'OTHER') NOT NULL,
    payer_role          ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    paid_on             DATE         NOT NULL,
    payer_share_percent TINYINT      NOT NULL COMMENT 'Part du payeur, de 0 à 100',
    status              ENUM('PENDING', 'CONTESTED', 'VALIDATED', 'WITHDRAWN') NOT NULL,
    contest_reason      ENUM('AMOUNT', 'RECEIPT', 'NOT_AGREED', 'OTHER') NULL,
    contest_note        VARCHAR(500) NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at          DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    answered_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_expenses_family_status (family_id, status),
    CONSTRAINT fk_expenses_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_expenses_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Les enfants concernés par une dépense (un ou plusieurs).
CREATE TABLE expense_children (
    expense_id BINARY(16) NOT NULL,
    child_id   BINARY(16) NOT NULL,
    PRIMARY KEY (expense_id, child_id),
    CONSTRAINT fk_expense_children_expense FOREIGN KEY (expense_id) REFERENCES expenses (id) ON DELETE CASCADE,
    CONSTRAINT fk_expense_children_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE
);

-- Un virement déclaré par le parent qui doit le solde, puis confirmé par l'autre. L'app n'effectue aucun paiement.
CREATE TABLE settlements (
    id           BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id    BINARY(16)  NOT NULL,
    payer_role   ENUM('PARENT_A', 'PARENT_B') NOT NULL COMMENT 'Le parent qui a déclaré le virement',
    amount_cents INT         NOT NULL,
    status       ENUM('DECLARED', 'NOT_RECEIVED', 'CONFIRMED', 'CANCELLED') NOT NULL,
    declared_at  DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    answered_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_settlements_family_status (family_id, status),
    CONSTRAINT fk_settlements_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE
);

-- Un virement déclaré attend la confirmation de l'autre parent dans « À traiter ».
ALTER TABLE to_do_items
    MODIFY COLUMN kind ENUM('CUSTODY_SCHEDULE', 'EXCHANGE', 'EXPENSE', 'SETTLEMENT', 'MEMBER', 'DEVICE',
        'PUBLICATION_REMOVAL', 'PENSION') NOT NULL;
