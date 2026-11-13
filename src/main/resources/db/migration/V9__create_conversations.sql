-- Discussions par sujet entre les deux parents. Le sujet ne change pas et une discussion ne se
-- supprime pas (ses messages sont immuables) : triggers comme pour messages.
CREATE TABLE conversations (
    id          BINARY(16)  NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id   BINARY(16)  NOT NULL,
    author_id   BINARY(16)  NULL COMMENT 'NULL si le compte a été supprimé',
    subject     VARCHAR(80) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY idx_conversations_family (family_id),
    CONSTRAINT fk_conversations_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversations_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL
);

-- Chaque message appartient à une discussion (table encore vide : V8 arrive dans la même livraison).
ALTER TABLE messages
    ADD COLUMN conversation_id BINARY(16) NOT NULL AFTER family_id,
    ADD KEY idx_messages_conversation_seq (conversation_id, seq),
    ADD CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE;

-- La suppression de la famille et SET NULL sur author_id passent par les cascades, qui ne
-- déclenchent pas les triggers.
CREATE TRIGGER conversations_no_update BEFORE UPDATE ON conversations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Une discussion ne peut pas être modifiée.';

CREATE TRIGGER conversations_no_delete BEFORE DELETE ON conversations FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Une discussion ne peut pas être supprimée.';
