package com.vynk.coparent_backend.account.dto;

import com.vynk.coparent_backend.family.dto.FamilyResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Personal data of one user (spec §1), sent by email as JSON: the account, then what the user sees in the app.
 * The other parent's private events are never included.
 */
public record DataExport(
        Instant exportedAt,
        Account account,
        FamilyResponse family,
        List<Event> events,
        List<Conversation> conversations,
        List<Exchange> exchanges,
        List<BagItem> bagItems,
        List<Expense> expenses,
        List<Settlement> settlements,
        List<ToDo> openRequests
) {

    public record Account(UUID id, String email, String firstName, Instant emailVerifiedAt, Instant createdAt) {
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
                          String contestReason, String contestNote, String authorFirstName, Instant createdAt) {
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
