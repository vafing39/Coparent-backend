package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.TokenService;
import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.common.storage.AfterCommit;
import com.vynk.coparent_backend.config.AuthProperties;
import com.vynk.coparent_backend.family.dto.RelativeFamilyResponse;
import com.vynk.coparent_backend.family.dto.RelativeInvitationPreviewResponse;
import com.vynk.coparent_backend.family.dto.RelativeRequest;
import com.vynk.coparent_backend.family.dto.RelativeResponse;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.FamilyRelative;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.RelativeStatus;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRelativeRepository;
import com.vynk.coparent_backend.todo.entity.ToDoItem;
import com.vynk.coparent_backend.todo.entity.ToDoKind;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Famille élargie : un parent propose un proche, l'autre parent valide (« À traiter », {@code MEMBER}) ; validé par
 * les deux, le proche reçoit par email un lien à usage unique (14 jours) pour rejoindre. En solo, la proposition
 * attend l'arrivée du second parent. Chaque parent retire un proche seul, à tout moment ; le proche peut partir.
 */
@Service
@RequiredArgsConstructor
public class RelativeService {

    static final Duration INVITATION_TTL = Duration.ofDays(14);
    static final String NOT_FOUND = "Proche introuvable.";
    static final String INVALID_INVITATION = "Cette invitation n'est plus valide.";
    private static final Set<RelativeStatus> OPEN =
            EnumSet.of(RelativeStatus.PROPOSED, RelativeStatus.INVITED, RelativeStatus.ACTIVE);

    private final FamilyAccess familyAccess;
    private final FamilyService familyService;
    private final FamilyMemberRepository memberRepository;
    private final FamilyRelativeRepository relativeRepository;
    private final ChildRepository childRepository;
    private final ToDoItemRepository toDoRepository;
    private final TokenService tokenService;
    private final EmailSender emailSender;
    private final AuthProperties authProperties;
    private final Clock clock;

    /** Les proches actifs et en attente, par ordre d'arrivée. */
    @Transactional(readOnly = true)
    public List<RelativeResponse> list(UUID familyId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        List<FamilyMember> parents = memberRepository.findByFamilyIdOrderByRoleAsc(familyId);
        return relativeRepository.findByFamilyIdAndStatusInOrderByCreatedAtAsc(familyId, OPEN).stream()
                .map(r -> toResponse(r, me, parents))
                .toList();
    }

    @Transactional
    public RelativeResponse propose(UUID familyId, UUID userId, RelativeRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        List<FamilyMember> parents = memberRepository.findByFamilyIdOrderByRoleAsc(familyId);
        if (parents.stream().anyMatch(p -> p.getUser().getEmail().equalsIgnoreCase(email))) {
            throw ApiException.badRequest("Cette adresse est celle d'un parent de la famille.");
        }
        if (relativeRepository.existsByFamilyIdAndEmailAndStatusIn(familyId, email, OPEN)) {
            throw ApiException.conflict("Cette adresse est déjà proposée ou invitée.");
        }
        FamilyRelative relative = relativeRepository.saveAndFlush(new FamilyRelative(me.getFamily(),
                request.firstName().strip(), email, request.relation(), me.getRole()));
        other(parents, me.getRole()).ifPresent(other -> askApproval(relative, me.getUser(), other.getUser()));
        return toResponse(relative, me, parents);
    }

    /** Par l'autre parent : validé par les deux, le proche reçoit son lien. */
    @Transactional
    public RelativeResponse approve(UUID familyId, UUID userId, UUID relativeId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        FamilyRelative relative = find(familyId, relativeId);
        requireToAnswer(relative, me);
        sendInvitation(relative, clock.instant());
        resolveToDos(relative);
        return toResponse(relative, me, memberRepository.findByFamilyIdOrderByRoleAsc(familyId));
    }

    /** Par l'autre parent : la proposition est refusée, rien n'est envoyé. */
    @Transactional
    public void decline(UUID familyId, UUID userId, UUID relativeId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        FamilyRelative relative = find(familyId, relativeId);
        requireToAnswer(relative, me);
        relative.end(RelativeStatus.DECLINED, clock.instant());
        resolveToDos(relative);
    }

    /** Par un parent : un nouveau lien remplace le précédent (expiré ou perdu). */
    @Transactional
    public RelativeResponse resend(UUID familyId, UUID userId, UUID relativeId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        FamilyRelative relative = find(familyId, relativeId);
        if (relative.getStatus() != RelativeStatus.INVITED) {
            throw ApiException.conflict("Aucune invitation en cours pour ce proche.");
        }
        sendInvitation(relative, clock.instant());
        return toResponse(relative, me, memberRepository.findByFamilyIdOrderByRoleAsc(familyId));
    }

    /** Par un parent, seul : annule une proposition ou une invitation, ou retire un proche (accès coupé aussitôt). */
    @Transactional
    public void remove(UUID familyId, UUID userId, UUID relativeId) {
        familyAccess.requireMember(familyId, userId);
        FamilyRelative relative = find(familyId, relativeId);
        relative.end(relative.isActive() ? RelativeStatus.REMOVED : RelativeStatus.CANCELLED, clock.instant());
        resolveToDos(relative);
    }

    /** Le second parent vient de rejoindre : les propositions en attente lui sont demandées. */
    @Transactional
    public void askNewParent(Family family, User newParent) {
        Map<FamilyRole, User> parents = memberRepository.findByFamilyIdOrderByRoleAsc(family.getId()).stream()
                .collect(Collectors.toMap(FamilyMember::getRole, FamilyMember::getUser));
        relativeRepository.findByFamilyIdAndStatusInOrderByCreatedAtAsc(family.getId(),
                        EnumSet.of(RelativeStatus.PROPOSED)).stream()
                .filter(r -> !newParent.equals(parents.get(r.getProposedByRole())))
                .forEach(r -> askApproval(r, parents.get(r.getProposedByRole()), newParent));
    }

    /** Public : seulement ce qu'il faut pour décider de rejoindre. */
    @Transactional(readOnly = true)
    public RelativeInvitationPreviewResponse preview(String rawToken) {
        FamilyRelative relative = relativeRepository.findByTokenHash(tokenService.hash(rawToken))
                .filter(r -> r.isInvitationUsable(clock.instant()))
                .orElseThrow(() -> ApiException.notFound(INVALID_INVITATION));
        UUID familyId = relative.getFamily().getId();
        return new RelativeInvitationPreviewResponse(relative.getFirstName(), relative.getRelation(),
                memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                        .map(m -> m.getUser().getFirstName())
                        .toList(),
                childRepository.findByFamilyIdOrderByBirthDateAscCreatedAtAsc(familyId).stream()
                        .map(c -> c.getFirstName())
                        .toList(),
                relative.getExpiresAt());
    }

    /** Le proche, connecté, rejoint la famille. Un compte déjà parent d'une famille est refusé pour l'instant. */
    @Transactional
    public RelativeFamilyResponse accept(String rawToken, UUID userId) {
        Instant now = clock.instant();
        FamilyRelative relative = relativeRepository.findByTokenHashForUpdate(tokenService.hash(rawToken))
                .filter(r -> r.isInvitationUsable(now))
                .orElseThrow(() -> ApiException.notFound(INVALID_INVITATION));
        User user = familyService.requireVerifiedUser(userId);
        if (memberRepository.existsByUserId(userId)) {
            throw ApiException.conflict("Ce compte est déjà celui d'un parent : utilisez un autre compte pour rejoindre "
                    + "une famille en tant que proche.");
        }
        UUID familyId = relative.getFamily().getId();
        if (relativeRepository.findByFamilyIdAndUserIdAndStatus(familyId, userId, RelativeStatus.ACTIVE).isPresent()) {
            throw ApiException.conflict("Vous faites déjà partie de cette famille.");
        }
        relative.join(user, now);
        return toFamilyResponse(relative);
    }

    /** Les familles dont je suis un proche. */
    @Transactional(readOnly = true)
    public List<RelativeFamilyResponse> myFamilies(UUID userId) {
        return relativeRepository.findByUserIdAndStatusOrderByJoinedAtAsc(userId, RelativeStatus.ACTIVE).stream()
                .map(this::toFamilyResponse)
                .toList();
    }

    /** Le proche quitte la famille. */
    @Transactional
    public void leave(UUID familyId, UUID userId) {
        requireActiveRelative(familyId, userId).end(RelativeStatus.LEFT, clock.instant());
    }

    /** Suppression du compte : il quitte toutes les familles où il est un proche. */
    @Transactional
    public void leaveAll(UUID userId) {
        Instant now = clock.instant();
        relativeRepository.findByUserIdAndStatusIn(userId, EnumSet.of(RelativeStatus.ACTIVE))
                .forEach(r -> r.end(RelativeStatus.LEFT, now));
    }

    /** Le proche actif qu'est ce compte dans cette famille, sinon 404 (comme une famille inconnue). */
    @Transactional(readOnly = true)
    public FamilyRelative requireActiveRelative(UUID familyId, UUID userId) {
        return relativeRepository.findByFamilyIdAndUserIdAndStatus(familyId, userId, RelativeStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound(FamilyAccess.FAMILY_NOT_FOUND));
    }

    private FamilyRelative find(UUID familyId, UUID relativeId) {
        return relativeRepository.findByIdAndFamilyId(relativeId, familyId)
                .filter(r -> r.getStatus().isOpen())
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private static void requireToAnswer(FamilyRelative relative, FamilyMember me) {
        if (relative.getStatus() != RelativeStatus.PROPOSED || relative.getProposedByRole() == me.getRole()) {
            throw ApiException.conflict("Cette proposition attend la réponse de l'autre parent.");
        }
    }

    private void askApproval(FamilyRelative relative, User proposer, User assignee) {
        String subject = "Proche : " + relative.getFirstName() + " (" + relative.getRelation().label() + ")";
        toDoRepository.save(new ToDoItem(relative.getFamily(), ToDoKind.MEMBER, relative.getId(),
                subject.length() <= 150 ? subject : subject.substring(0, 149) + "…", proposer, assignee));
    }

    private void resolveToDos(FamilyRelative relative) {
        Instant now = clock.instant();
        toDoRepository.findByFamilyIdAndKindAndTargetIdAndResolvedAtIsNull(
                        relative.getFamily().getId(), ToDoKind.MEMBER, relative.getId())
                .forEach(item -> item.resolve(now));
    }

    private void sendInvitation(FamilyRelative relative, Instant now) {
        String rawToken = tokenService.generateOpaqueToken();
        relative.invite(tokenService.hash(rawToken), now.plus(INVITATION_TTL), now);
        String link = authProperties.publicBaseUrl() + "/rejoindre?token=" + rawToken;
        String parentNames = memberRepository.findByFamilyIdOrderByRoleAsc(relative.getFamily().getId()).stream()
                .map(m -> m.getUser().getFirstName())
                .collect(Collectors.joining(" et "));
        String email = relative.getEmail();
        String firstName = relative.getFirstName();
        String relation = relative.getRelation().label();
        AfterCommit.run(() -> emailSender.sendRelativeInvitation(email, firstName, parentNames, relation, link));
    }

    private static Optional<FamilyMember> other(List<FamilyMember> parents, FamilyRole role) {
        return parents.stream().filter(p -> p.getRole() != role).findFirst();
    }

    private RelativeResponse toResponse(FamilyRelative r, FamilyMember me, List<FamilyMember> parents) {
        String proposedBy = parents.stream()
                .filter(p -> p.getRole() == r.getProposedByRole())
                .map(p -> p.getUser().getFirstName())
                .findFirst().orElse(null);
        boolean canApprove = r.getStatus() == RelativeStatus.PROPOSED && r.getProposedByRole() != me.getRole()
                && parents.size() == 2;
        Instant expiresAt = r.getStatus() == RelativeStatus.INVITED ? r.getExpiresAt() : null;
        return new RelativeResponse(r.getId(), r.getFirstName(), r.isActive() ? null : r.getEmail(),
                r.getRelation(), r.getStatus(), r.getProposedByRole(), proposedBy, r.getCreatedAt(),
                r.getApprovedAt(), r.getJoinedAt(), expiresAt,
                expiresAt != null && !clock.instant().isBefore(expiresAt), canApprove);
    }

    private RelativeFamilyResponse toFamilyResponse(FamilyRelative r) {
        UUID familyId = r.getFamily().getId();
        return new RelativeFamilyResponse(familyId, r.getFamily().getName(), r.getId(), r.getFirstName(),
                r.getRelation(), r.getJoinedAt(),
                memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                        .map(m -> new RelativeFamilyResponse.Parent(m.getRole(), m.getUser().getFirstName()))
                        .toList(),
                childRepository.findByFamilyIdOrderByBirthDateAscCreatedAtAsc(familyId).stream()
                        .map(c -> new RelativeFamilyResponse.Child(c.getId(), c.getFirstName()))
                        .toList());
    }
}
