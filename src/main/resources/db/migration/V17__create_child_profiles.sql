-- Fiche de chaque enfant (onglet Famille › Enfants, priorité 2) : école, santé, contacts utiles.
-- Les deux parents la modifient ; la dernière modification est tracée (qui, quand).

-- Une ligne par enfant, créée au premier enregistrement (pas de ligne = fiche vide).
CREATE TABLE child_profiles (
    child_id        BINARY(16)   NOT NULL,
    school          VARCHAR(120) NULL,
    grade           VARCHAR(60)  NULL COMMENT 'Classe',
    teacher         VARCHAR(100) NULL,
    allergies       VARCHAR(500) NULL COMMENT 'Donnée de santé',
    doctor          VARCHAR(120) NULL,
    doctor_phone    VARCHAR(30)  NULL,
    updated_by      BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    updated_by_role ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (child_id),
    CONSTRAINT fk_child_profiles_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_child_profiles_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Contacts utiles d'un enfant (nounou, garderie, club…), dans l'ordre choisi ; 10 au plus.
CREATE TABLE child_contacts (
    id       BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    child_id BINARY(16)   NOT NULL,
    position INT          NOT NULL,
    name     VARCHAR(100) NOT NULL,
    role     VARCHAR(100) NULL,
    phone    VARCHAR(30)  NULL,
    PRIMARY KEY (id),
    KEY idx_child_contacts_child (child_id, position),
    CONSTRAINT fk_child_contacts_profile FOREIGN KEY (child_id) REFERENCES child_profiles (child_id) ON DELETE CASCADE
);
