package com.vynk.coparent_backend.common.storage;

import java.util.Optional;

/**
 * Stockage des fichiers envoyés (justificatifs photo). Les clés sont des chemins relatifs, ex.
 * {@code receipts/<familyId>/<expenseId>-<uuid>.jpg}. En local : {@link DiskFileStorage} ; une implémentation S3
 * pourra la remplacer au déploiement sans toucher au reste.
 */
public interface FileStorage {

    void put(String key, byte[] content);

    Optional<byte[]> get(String key);

    /** Sans effet si le fichier n'existe pas. */
    void delete(String key);

    /** Supprime tous les fichiers sous ce préfixe, ex. {@code receipts/<familyId>}. */
    void deleteFolder(String prefix);
}
