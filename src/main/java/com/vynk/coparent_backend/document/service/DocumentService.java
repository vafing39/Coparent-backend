package com.vynk.coparent_backend.document.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.common.storage.AfterCommit;
import com.vynk.coparent_backend.common.storage.FileStorage;
import com.vynk.coparent_backend.common.storage.PhotoCleaner;
import com.vynk.coparent_backend.document.dto.DocumentLinkResponse;
import com.vynk.coparent_backend.document.dto.DocumentResponse;
import com.vynk.coparent_backend.document.entity.Document;
import com.vynk.coparent_backend.document.entity.DocumentCategory;
import com.vynk.coparent_backend.document.entity.DocumentEvent;
import com.vynk.coparent_backend.document.entity.DocumentEventKind;
import com.vynk.coparent_backend.document.repository.DocumentEventRepository;
import com.vynk.coparent_backend.document.repository.DocumentRepository;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Documents partagés de la famille (priorité 2) : un PDF, stocké tel quel, ou une photo nettoyée de ses
 * métadonnées. Chaque ouverture passe par un lien temporaire ({@link DocumentLinks}) et entre dans l'historique,
 * visible par les deux parents. Seul l'auteur supprime un document ; la suppression reste dans l'historique.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final int TITLE_MAX_LENGTH = 120;
    static final Duration LINK_TTL = Duration.ofMinutes(5);
    static final String NOT_FOUND = "Document introuvable.";
    static final String INVALID_FILE = "Le document doit être un PDF ou une photo (JPEG ou PNG).";
    static final String PDF = "application/pdf";
    static final String JPEG = "image/jpeg";
    public static final String FILES_PATH = "/api/v1/document-files/";

    private final FamilyAccess familyAccess;
    private final ChildRepository childRepository;
    private final DocumentRepository documentRepository;
    private final DocumentEventRepository eventRepository;
    private final PhotoCleaner photoCleaner;
    private final FileStorage storage;
    private final DocumentLinks links;
    private final Clock clock;

    /** Tous les documents de la famille, le plus récent d'abord, supprimés compris, avec leur historique. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID familyId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Map<UUID, List<DocumentResponse.HistoryLine>> history =
                eventRepository.findByDocumentFamilyIdOrderByCreatedAtDesc(familyId).stream()
                        .collect(Collectors.groupingBy(e -> e.getDocument().getId(),
                                Collectors.mapping(DocumentService::line, Collectors.toList())));
        return documentRepository.findByFamilyIdOrderByCreatedAtDesc(familyId).stream()
                .map(d -> toResponse(d, me, history.getOrDefault(d.getId(), List.of())))
                .toList();
    }

    @Transactional
    public DocumentResponse upload(UUID familyId, UUID userId, byte[] file, String title, DocumentCategory category,
                                   UUID childId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty()) {
            throw ApiException.badRequest("Indiquez le nom du document.");
        }
        if (cleanTitle.length() > TITLE_MAX_LENGTH) {
            throw ApiException.badRequest("Le nom du document ne peut pas dépasser 120 caractères.");
        }
        if (category == null) {
            throw ApiException.badRequest("Choisissez une catégorie.");
        }
        Child child = childId == null ? null : childRepository.findByIdAndFamilyId(childId, familyId)
                .orElseThrow(() -> ApiException.badRequest("Cet enfant ne fait pas partie de la famille."));
        if (file == null || file.length == 0) {
            throw ApiException.badRequest(INVALID_FILE);
        }
        if (file.length > MAX_BYTES) {
            throw ApiException.badRequest("Le document ne peut pas dépasser 20 Mo.");
        }

        boolean pdf = isPdf(file);
        if (!pdf && !PhotoCleaner.isJpeg(file) && !PhotoCleaner.isPng(file)) {
            throw ApiException.badRequest(INVALID_FILE);
        }
        byte[] content = pdf ? file : photoCleaner.clean(file);
        String key = "documents/" + familyId + "/" + UUID.randomUUID() + (pdf ? ".pdf" : ".jpg");
        storage.put(key, content);
        try {
            Document document = documentRepository.save(new Document(me.getFamily(), me.getUser(), me.getRole(),
                    cleanTitle, category, child, key, pdf ? PDF : JPEG, content.length));
            return toResponse(document, me, List.of());
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    /** Enregistre l'ouverture dans l'historique, puis renvoie un lien valable {@link #LINK_TTL}. */
    @Transactional
    public DocumentLinkResponse open(UUID familyId, UUID userId, UUID documentId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Document document = findLive(familyId, documentId);
        Instant now = clock.instant();
        eventRepository.save(new DocumentEvent(document, me.getUser(), me.getRole(), DocumentEventKind.VIEWED, now));
        Instant expiresAt = now.plus(LINK_TTL);
        return new DocumentLinkResponse(FILES_PATH + links.token(document.getId(), expiresAt), expiresAt);
    }

    @Transactional
    public void delete(UUID familyId, UUID userId, UUID documentId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Document document = findLive(familyId, documentId);
        if (document.getAuthorRole() != me.getRole()) {
            throw ApiException.conflict("Seul le parent qui a ajouté le document peut le supprimer.");
        }
        Instant now = clock.instant();
        String key = document.delete(me.getRole(), now);
        eventRepository.save(new DocumentEvent(document, me.getUser(), me.getRole(), DocumentEventKind.DELETED, now));
        AfterCommit.run(() -> storage.delete(key));
    }

    /** Le fichier désigné par un lien temporaire (sans session). 404 si le lien est invalide ou expiré. */
    @Transactional(readOnly = true)
    public DocumentFile file(String token) {
        UUID id = links.verify(token, clock.instant()).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        Document document = documentRepository.findById(id)
                .filter(d -> !d.isDeleted())
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        byte[] content = storage.get(document.getFileKey()).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        String extension = PDF.equals(document.getContentType()) ? ".pdf" : ".jpg";
        return new DocumentFile(document.getTitle() + extension, document.getContentType(), content);
    }

    public record DocumentFile(String fileName, String contentType, byte[] content) {
    }

    static boolean isPdf(byte[] b) {
        return b.length > 5 && new String(b, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
    }

    private Document findLive(UUID familyId, UUID documentId) {
        return documentRepository.findByIdAndFamilyId(documentId, familyId)
                .filter(d -> !d.isDeleted())
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private static DocumentResponse.HistoryLine line(DocumentEvent event) {
        User user = event.getUser();
        return new DocumentResponse.HistoryLine(event.getKind(), event.getRole(),
                user == null ? null : user.getFirstName(), event.getCreatedAt());
    }

    private static DocumentResponse toResponse(Document document, FamilyMember me,
                                               List<DocumentResponse.HistoryLine> history) {
        User author = document.getCreatedBy();
        Child child = document.getChild();
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getCategory(),
                child == null ? null : child.getId(),
                child == null ? null : child.getFirstName(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getAuthorRole(),
                author == null ? null : author.getFirstName(),
                !document.isDeleted() && document.getAuthorRole() == me.getRole(),
                document.getCreatedAt(),
                document.getDeletedAt(),
                document.getDeletedByRole(),
                history);
    }
}
