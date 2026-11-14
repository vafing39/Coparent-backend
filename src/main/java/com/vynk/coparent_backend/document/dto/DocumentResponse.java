package com.vynk.coparent_backend.document.dto;

import com.vynk.coparent_backend.document.entity.DocumentCategory;
import com.vynk.coparent_backend.document.entity.DocumentEventKind;
import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un document et son historique (ouvertures et suppression, la plus récente d'abord). {@code childId} null : tous
 * les enfants. Les prénoms sont null si le compte a été supprimé. Supprimé : {@code deletedAt} et
 * {@code deletedByRole} renseignés, plus de fichier à ouvrir.
 */
public record DocumentResponse(
        UUID id,
        String title,
        DocumentCategory category,
        UUID childId,
        String childFirstName,
        String contentType,
        int sizeBytes,
        FamilyRole authorRole,
        String authorFirstName,
        boolean canDelete,
        Instant createdAt,
        Instant deletedAt,
        FamilyRole deletedByRole,
        List<HistoryLine> history
) {

    public record HistoryLine(DocumentEventKind kind, FamilyRole role, String firstName, Instant at) {
    }
}
