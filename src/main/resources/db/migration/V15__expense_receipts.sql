-- Justificatif photo d'une dépense : un fichier au plus, dans le stockage de fichiers (FileStorage).
ALTER TABLE expenses
    ADD COLUMN receipt_key        VARCHAR(200) NULL COMMENT 'Fichier du justificatif dans le stockage' AFTER contest_note,
    ADD COLUMN receipt_updated_at DATETIME(6)  NULL AFTER receipt_key;
