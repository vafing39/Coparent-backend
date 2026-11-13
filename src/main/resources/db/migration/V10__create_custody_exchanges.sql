-- Demandes d'échange de garde (cahier des charges, 6). Le demandeur prend des nuits de l'autre parent
-- (take_from..take_to) et, en option, lui en laisse en retour (give_from..give_to). Une fois l'échange
-- accepté, le calcul de la garde attribue ces nuits au nouveau parent.
CREATE TABLE custody_exchanges (
    id             BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id      BINARY(16)   NOT NULL,
    requested_by   BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    requester_role ENUM('PARENT_A', 'PARENT_B') NOT NULL COMMENT 'Reçoit les nuits « take », laisse les nuits « give »',
    take_from      DATE         NOT NULL,
    take_to        DATE         NOT NULL,
    give_from      DATE         NULL,
    give_to        DATE         NULL,
    message        VARCHAR(500) NULL,
    status         ENUM('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED') NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    answered_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_custody_exchanges_family_status (family_id, status),
    CONSTRAINT fk_custody_exchanges_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_custody_exchanges_requested_by FOREIGN KEY (requested_by) REFERENCES users (id) ON DELETE SET NULL
);

-- L'objet concerné par une demande « À traiter » (ici l'échange) : l'app ouvre le bon écran.
ALTER TABLE to_do_items
    ADD COLUMN target_id BINARY(16) NULL COMMENT 'Objet concerné, ex. l''échange de garde' AFTER kind;
