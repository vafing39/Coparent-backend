-- Documents partagés de la famille (priorité 2) : PDF ou photo, ouverts par un lien temporaire.

-- Un document envoyé par un parent. Supprimé par son auteur, il reste dans la liste (deleted_at), sans fichier.
CREATE TABLE documents (
    id              BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id       BINARY(16)   NOT NULL,
    created_by      BINARY(16)   NULL COMMENT 'NULL si le compte a été supprimé',
    author_role     ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    title           VARCHAR(120) NOT NULL,
    category        ENUM('SCHOOL', 'INSURANCE', 'IDENTITY', 'JUDGMENT', 'OTHER') NOT NULL,
    child_id        BINARY(16)   NULL COMMENT 'NULL = tous les enfants',
    file_key        VARCHAR(200) NULL COMMENT 'Fichier dans le stockage ; NULL une fois supprimé',
    content_type    VARCHAR(50)  NOT NULL,
    size_bytes      INT          NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    deleted_at      DATETIME(6)  NULL,
    deleted_by_role ENUM('PARENT_A', 'PARENT_B') NULL,
    PRIMARY KEY (id),
    KEY idx_documents_family_created (family_id, created_at),
    CONSTRAINT fk_documents_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_documents_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_documents_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE SET NULL
);

-- Historique d'un document, visible par les deux parents : chaque ouverture, et sa suppression.
CREATE TABLE document_events (
    id          BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    document_id BINARY(16)  NOT NULL,
    user_id     BINARY(16)  NULL COMMENT 'NULL si le compte a été supprimé',
    role        ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    kind        ENUM('VIEWED', 'DELETED') NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_document_events_document (document_id, created_at),
    CONSTRAINT fk_document_events_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_document_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
);
