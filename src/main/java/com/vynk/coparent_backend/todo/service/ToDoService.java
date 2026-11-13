package com.vynk.coparent_backend.todo.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.todo.dto.ToDoItemResponse;
import com.vynk.coparent_backend.todo.dto.ToDoResponse;
import com.vynk.coparent_backend.todo.entity.ToDoItem;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** « À traiter » (cahier des charges, 8) : les demandes sans réponse de la famille, réparties selon qui doit répondre. */
@Service
@RequiredArgsConstructor
public class ToDoService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String NOT_FOUND = "Demande introuvable.";

    private final FamilyAccess familyAccess;
    private final ToDoItemRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ToDoResponse list(UUID familyId, UUID userId) {
        familyAccess.requireMember(familyId, userId);
        return listFor(familyId, userId);
    }

    /**
     * « Relancer » l'autre parent (cahier des charges, 8) : seulement pour une demande qui attend sa réponse, et une
     * fois par jour (Europe/Paris) au maximum. Renvoie « À traiter » à jour.
     * TODO : envoyer la notification au texte neutre quand les notifications existeront.
     */
    @Transactional
    public ToDoResponse nudge(UUID familyId, UUID userId, UUID itemId) {
        familyAccess.requireMember(familyId, userId);
        ToDoItem item = repository.findByIdAndFamilyIdAndResolvedAtIsNull(itemId, familyId)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        if (item.getAssignee().getId().equals(userId)) {
            throw ApiException.conflict("Cette demande attend votre réponse.");
        }
        Instant now = clock.instant();
        Instant last = item.getLastNudgedAt();
        if (last != null && day(last).equals(day(now))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Une relance par jour au maximum.");
        }
        item.nudge(now);
        repository.flush();
        return listFor(familyId, userId);
    }

    private ToDoResponse listFor(UUID familyId, UUID userId) {
        Map<Boolean, List<ToDoItemResponse>> byAssignee = repository
                .findByFamilyIdAndResolvedAtIsNullOrderByRequestedAtAsc(familyId)
                .stream()
                .collect(Collectors.partitioningBy(
                        item -> item.getAssignee().getId().equals(userId),
                        Collectors.mapping(ToDoService::toResponse, Collectors.toList())));
        return new ToDoResponse(byAssignee.get(true), byAssignee.get(false));
    }

    private static LocalDate day(Instant instant) {
        return instant.atZone(PARIS).toLocalDate();
    }

    private static ToDoItemResponse toResponse(ToDoItem item) {
        User requestedBy = item.getRequestedBy();
        return new ToDoItemResponse(
                item.getId(),
                item.getKind(),
                item.getSubject(),
                requestedBy == null ? null : requestedBy.getFirstName(),
                item.getAssignee().getFirstName(),
                item.getRequestedAt(),
                item.getLastNudgedAt());
    }
}
