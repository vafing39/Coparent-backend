package com.vynk.coparent_backend.account.dto;

import com.vynk.coparent_backend.family.dto.FamilyResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Personal data of one user (spec §1), sent by email as JSON: the account, then what the user sees in the app.
 * The other parent's private events are never included. A relative (wider family) has no {@code family}: their
 * data are in {@code relativeFamilies}.
 */
public record DataExport(
        Instant exportedAt,
        Account account,
        FamilyResponse family,
        List<ChildProfile> childProfiles,
        List<Event> events,
        List<Conversation> conversations,
        List<Exchange> exchanges,
        List<BagItem> bagItems,
        List<Expense> expenses,
        List<Settlement> settlements,
        List<Document> documents,
        List<Post> posts,
        List<Relative> relatives,
        List<ToDo> openRequests,
        List<RelativeFamily> relativeFamilies
) {

    public record Account(UUID id, String email, String firstName, Instant emailVerifiedAt, Instant createdAt) {
    }

    /** Fiche d'un enfant (école, santé, contacts utiles), telle que la voient les deux parents. */
    public record ChildProfile(String child, String school, String grade, String teacher, String allergies,
                               String doctor, String doctorPhone, List<ChildContact> contacts,
                               String updatedByFirstName, Instant updatedAt) {
    }

    public record ChildContact(String name, String role, String phone) {
    }

    public record Event(UUID id, String title, String type, boolean allDay, Instant startsAt, Instant endsAt,
                        String location, String visibility, String repeat, List<String> children,
                        String authorFirstName, Instant createdAt) {
    }

    public record Conversation(UUID id, String subject, String authorFirstName, Instant createdAt,
                               List<Message> messages) {
    }

    public record Message(long seq, String senderFirstName, String body, Instant createdAt) {
    }

    public record Exchange(UUID id, String requestedByFirstName, LocalDate takeFrom, LocalDate takeTo,
                           LocalDate giveFrom, LocalDate giveTo, String message, String status, Instant createdAt,
                           Instant answeredAt) {
    }

    public record Expense(UUID id, String label, int amountCents, String category, List<String> children,
                          String payerRole, LocalDate paidOn, int payerSharePercent, String status,
                          String contestReason, String contestNote, boolean hasReceipt, String authorFirstName,
                          Instant createdAt) {
    }

    /** Un document partagé, sans son fichier, avec son historique (ouvertures, suppression). */
    public record Document(UUID id, String title, String category, String child, String contentType, int sizeBytes,
                           String authorFirstName, Instant createdAt, Instant deletedAt,
                           List<DocumentEvent> history) {
    }

    /** Une publication du fil (les photos ne sont pas jointes, seulement leur nombre). */
    public record Post(UUID id, String circle, String authorFirstName, String body, boolean firstTime,
                       List<String> children,
                       int photoCount, List<String> reactions, boolean hidden, Instant createdAt,
                       List<PostComment> comments) {
    }

    public record PostComment(String firstName, String body, Instant createdAt) {
    }

    /** Un proche de la famille élargie (actif ou en attente) ; son adresse n'est pas exportée. */
    public record Relative(String firstName, String relation, String status, Instant createdAt, Instant joinedAt) {
    }

    /**
     * Une famille dont l'utilisateur est (ou a été) un proche : son lien, ses publications (avec leurs réactions et
     * commentaires), ses commentaires et ses réactions. Les publications des autres ne sont pas exportées.
     */
    public record RelativeFamily(String familyName, String firstName, String relation, String status,
                                 Instant joinedAt, Instant endedAt, List<Post> posts,
                                 List<RelativeComment> comments, List<RelativeReaction> reactions) {
    }

    public record RelativeComment(UUID postId, String body, Instant createdAt) {
    }

    public record RelativeReaction(UUID postId, String kind, Instant createdAt) {
    }

    public record DocumentEvent(String kind, String firstName, Instant at) {
    }

    public record Settlement(UUID id, String payerRole, int amountCents, String status, Instant declaredAt,
                             Instant answeredAt) {
    }

    public record BagItem(String child, String label, Instant createdAt) {
    }

    public record ToDo(String kind, String subject, String requestedByFirstName, String assigneeFirstName,
                       Instant requestedAt) {
    }
}
