-- Famille élargie (Fil, livraison 2, étape 1) : grands-parents, beaux-parents, oncles et tantes.
-- Un parent propose un proche, l'autre parent valide ; validé par les deux, le proche reçoit par email un lien à usage
-- unique pour rejoindre. Table à part de family_members : un proche n'est pas membre de la famille et n'atteint
-- aucune autre route que le fil, où il ne voit que les publications du cercle « Famille ».

CREATE TABLE family_relatives (
    id                BINARY(16)   NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id         BINARY(16)   NOT NULL,
    user_id           BINARY(16)   NULL COMMENT 'Le compte du proche, une fois qu''il a rejoint',
    first_name        VARCHAR(100) NOT NULL,
    email             VARCHAR(254) NULL COMMENT 'Adresse de l''invitation ; effacée quand le proche n''en fait plus partie',
    relation          ENUM('GRANDPARENT', 'STEP_PARENT', 'UNCLE_AUNT') NOT NULL,
    proposed_by_role  ENUM('PARENT_A', 'PARENT_B') NOT NULL,
    status            ENUM('PROPOSED', 'INVITED', 'ACTIVE', 'DECLINED', 'CANCELLED', 'REMOVED', 'LEFT') NOT NULL,
    token_hash        CHAR(64)     NULL COMMENT 'SHA-256 du lien d''invitation (statut INVITED)',
    expires_at        DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    approved_at       DATETIME(6)  NULL COMMENT 'Validé par l''autre parent',
    joined_at         DATETIME(6)  NULL,
    ended_at          DATETIME(6)  NULL COMMENT 'Refusé, annulé, retiré ou parti',
    PRIMARY KEY (id),
    UNIQUE KEY uk_family_relatives_token (token_hash),
    KEY idx_family_relatives_family (family_id, status),
    KEY idx_family_relatives_user (user_id, status),
    CONSTRAINT fk_family_relatives_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_family_relatives_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
);

-- Cercle d'une publication : PARENTS (« Partagé », les deux parents) ou FAMILY (parents et proches validés).
-- Auteur : un parent (author_role) ou un proche (author_relative_id). Les publications existantes restent « Partagé ».
ALTER TABLE posts
    ADD COLUMN circle ENUM('PARENTS', 'FAMILY') NOT NULL DEFAULT 'PARENTS' AFTER family_id,
    ADD COLUMN author_relative_id BINARY(16) NULL AFTER author_role,
    MODIFY COLUMN author_role ENUM('PARENT_A', 'PARENT_B') NULL COMMENT 'NULL pour un proche',
    ADD CONSTRAINT fk_posts_author_relative FOREIGN KEY (author_relative_id) REFERENCES family_relatives (id)
        ON DELETE SET NULL;

-- Réactions : une de chaque sorte par personne. reactor = le rôle du parent ou l'id du proche.
ALTER TABLE post_reactions
    ADD COLUMN reactor VARCHAR(36) NOT NULL DEFAULT '' AFTER post_id,
    ADD COLUMN relative_id BINARY(16) NULL AFTER role;
UPDATE post_reactions SET reactor = role;
ALTER TABLE post_reactions
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (post_id, reactor, kind),
    ALTER COLUMN reactor DROP DEFAULT,
    MODIFY COLUMN role ENUM('PARENT_A', 'PARENT_B') NULL COMMENT 'NULL pour un proche',
    ADD CONSTRAINT fk_post_reactions_relative FOREIGN KEY (relative_id) REFERENCES family_relatives (id)
        ON DELETE CASCADE;

-- Commentaires : d'un parent (role) ou d'un proche (relative_id).
ALTER TABLE post_comments
    ADD COLUMN relative_id BINARY(16) NULL AFTER role,
    MODIFY COLUMN role ENUM('PARENT_A', 'PARENT_B') NULL COMMENT 'NULL pour un proche',
    ADD CONSTRAINT fk_post_comments_relative FOREIGN KEY (relative_id) REFERENCES family_relatives (id)
        ON DELETE SET NULL;
