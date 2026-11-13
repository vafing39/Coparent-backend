-- Messages entre parents. Immuables : aucune modification ni suppression (triggers ci-dessous) ;
-- chaque message porte le hash du précédent de la famille (hash chaîné).
CREATE TABLE messages (
    id          BINARY(16)    NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    family_id   BINARY(16)    NOT NULL,
    sender_id   BINARY(16)    NULL COMMENT 'NULL si le compte a été supprimé',
    seq         BIGINT        NOT NULL COMMENT 'Ordre dans la famille, à partir de 1',
    body        VARCHAR(2000) NOT NULL,
    created_at  DATETIME(6)   NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    prev_hash   CHAR(64)      NULL COMMENT 'hash du message seq - 1, NULL pour le premier',
    hash        CHAR(64)      NOT NULL COMMENT 'SHA-256 hex',
    PRIMARY KEY (id),
    UNIQUE KEY uk_messages_family_seq (family_id, seq),
    CONSTRAINT fk_messages_family FOREIGN KEY (family_id) REFERENCES families (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE SET NULL
);

-- Lecture par le destinataire : une ligne par message lu, jamais modifiée.
CREATE TABLE message_reads (
    message_id BINARY(16)  NOT NULL,
    user_id    BINARY(16)  NOT NULL,
    read_at    DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (message_id, user_id),
    CONSTRAINT fk_message_reads_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_reads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Immuabilité en base. Les actions en cascade des clés étrangères (suppression d'une famille ou d'un
-- compte) ne déclenchent pas les triggers dans MySQL : elles restent possibles.
CREATE TRIGGER messages_no_update BEFORE UPDATE ON messages FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Un message ne peut pas être modifié.';

CREATE TRIGGER messages_no_delete BEFORE DELETE ON messages FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Un message ne peut pas être supprimé.';
