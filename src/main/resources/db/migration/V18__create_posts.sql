-- Fil de la famille (onglet Famille › Fil, priorité 2), livraison 1 : entre les deux parents.
-- Une publication : du texte et/ou des photos (nettoyées), les enfants concernés, des réactions et des commentaires.
-- Ni publication ni commentaire ne se modifient. L'auteur supprime sa publication ; l'autre parent peut la masquer
-- en demandant son retrait.

CREATE TABLE posts (
    id                        BINARY(16)    NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id                 BINARY(16)    NOT NULL,
    created_by                BINARY(16)    NULL COMMENT 'NULL si le compte a été supprimé',
    author_role               ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    body                      VARCHAR(2000) NULL,
    first_time                BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '« Première fois » d''un enfant',
    created_at                DATETIME(6)   NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    hidden_at                 DATETIME(6)   NULL COMMENT 'Masquée : retrait demandé par l''autre parent',
    removal_requested_by_role ENUM('PARENT_A', 'PARENT_B') NULL,
    removal_reason            ENUM('PERSONAL', 'PLACE', 'UNSPECIFIED') NULL,
    PRIMARY KEY (id),
    KEY idx_posts_family_created (family_id, created_at),
    CONSTRAINT fk_posts_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_posts_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Photos d'une publication (6 au plus), dans l'ordre choisi.
CREATE TABLE post_photos (
    id       BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    post_id  BINARY(16)   NOT NULL,
    position INT          NOT NULL,
    file_key VARCHAR(200) NOT NULL COMMENT 'Fichier dans le stockage',
    width    INT          NOT NULL,
    height   INT          NOT NULL,
    PRIMARY KEY (id),
    KEY idx_post_photos_post (post_id, position),
    CONSTRAINT fk_post_photos_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);

-- Enfants concernés par une publication.
CREATE TABLE post_children (
    post_id  BINARY(16) NOT NULL,
    child_id BINARY(16) NOT NULL,
    PRIMARY KEY (post_id, child_id),
    CONSTRAINT fk_post_children_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_children_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE
);

-- Réactions : une de chaque sorte par parent.
CREATE TABLE post_reactions (
    post_id    BINARY(16)  NOT NULL,
    role       ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    kind       ENUM('HEART', 'BRAVO') NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (post_id, role, kind),
    CONSTRAINT fk_post_reactions_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);

-- Commentaires, publiés tout de suite, jamais modifiés.
CREATE TABLE post_comments (
    id         BINARY(16)    NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    post_id    BINARY(16)    NOT NULL,
    user_id    BINARY(16)    NULL COMMENT 'NULL si le compte a été supprimé',
    role       ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    created_at DATETIME(6)   NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_post_comments_post (post_id, created_at),
    CONSTRAINT fk_post_comments_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_comments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
);
