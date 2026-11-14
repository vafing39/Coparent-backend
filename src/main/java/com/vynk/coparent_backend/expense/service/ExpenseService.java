package com.vynk.coparent_backend.expense.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.expense.dto.ContestRequest;
import com.vynk.coparent_backend.expense.dto.ExpenseRequest;
import com.vynk.coparent_backend.expense.dto.ExpenseResponse;
import com.vynk.coparent_backend.expense.entity.Expense;
import com.vynk.coparent_backend.expense.entity.ExpenseStatus;
import com.vynk.coparent_backend.expense.repository.ExpenseRepository;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.todo.entity.ToDoItem;
import com.vynk.coparent_backend.todo.entity.ToDoKind;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dépenses partagées (priorité 2). Un parent ajoute une dépense ; l'autre l'accepte ou la conteste depuis
 * « À traiter ». Contestée, elle passe dans « À traiter » de l'auteur, qui la corrige (elle repasse en attente) ou
 * la retire. En mode solo, les dépenses attendent l'arrivée de l'autre parent ({@link #proposeToNewParent}).
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final int MAX_AMOUNT_CENTS = 10_000_000;
    /** Part du payeur : 50/50, 60/40, 70/30, 100 %, ou l'inverse. */
    static final Set<Integer> PAYER_SHARES = Set.of(0, 30, 40, 50, 60, 70, 100);
    static final String NOT_FOUND = "Dépense introuvable.";
    private static final List<ExpenseStatus> VISIBLE =
            List.of(ExpenseStatus.PENDING, ExpenseStatus.CONTESTED, ExpenseStatus.VALIDATED);

    private final FamilyAccess familyAccess;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final ChildRepository childRepository;
    private final ExpenseRepository expenseRepository;
    private final ToDoItemRepository toDoRepository;
    private final Clock clock;

    /** Toutes les dépenses sauf celles retirées, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<ExpenseResponse> list(UUID familyId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        boolean together = otherParent(familyId, me).isPresent();
        return expenseRepository.findByFamilyIdAndStatusInOrderByPaidOnDescCreatedAtDesc(familyId, VISIBLE).stream()
                .map(e -> toResponse(e, me, together))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(UUID familyId, UUID userId, UUID expenseId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Expense expense = find(familyId, expenseId);
        if (expense.getStatus() == ExpenseStatus.WITHDRAWN) {
            throw ApiException.notFound(NOT_FOUND);
        }
        return toResponse(expense, me, otherParent(familyId, me).isPresent());
    }

    @Transactional
    public ExpenseResponse create(UUID familyId, UUID userId, ExpenseRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Family family = lockFamily(familyId);
        Expense expense = new Expense(family, me.getUser(), me.getRole());
        fill(expense, familyId, request);
        expenseRepository.save(expense);
        Optional<FamilyMember> other = otherParent(familyId, me);
        other.ifPresent(o -> askAnswer(expense, me.getUser(), o.getUser()));
        return toResponse(expense, me, other.isPresent());
    }

    /** Correction par l'auteur : la dépense repasse en attente et revient dans « À traiter » de l'autre parent. */
    @Transactional
    public ExpenseResponse update(UUID familyId, UUID userId, UUID expenseId, ExpenseRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        lockFamily(familyId);
        Expense expense = find(familyId, expenseId);
        requireAuthor(expense, me, "Seul le parent qui a ajouté la dépense peut la corriger.");
        fill(expense, familyId, request);
        resolveToDos(expense, clock.instant());
        Optional<FamilyMember> other = otherParent(familyId, me);
        other.ifPresent(o -> askAnswer(expense, me.getUser(), o.getUser()));
        return toResponse(expense, me, other.isPresent());
    }

    @Transactional
    public ExpenseResponse accept(UUID familyId, UUID userId, UUID expenseId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        lockFamily(familyId);
        Expense expense = find(familyId, expenseId);
        requireOther(expense, me);
        Instant now = clock.instant();
        expense.accept(now);
        resolveToDos(expense, now);
        return toResponse(expense, me, true);
    }

    /** Contestée, la dépense reste hors du solde et passe dans « À traiter » de l'auteur. */
    @Transactional
    public ExpenseResponse contest(UUID familyId, UUID userId, UUID expenseId, ContestRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        lockFamily(familyId);
        Expense expense = find(familyId, expenseId);
        requireOther(expense, me);
        Instant now = clock.instant();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        expense.contest(request.reason(), note, now);
        resolveToDos(expense, now);
        if (expense.getCreatedBy() != null) {
            toDoRepository.save(new ToDoItem(expense.getFamily(), ToDoKind.EXPENSE, expense.getId(),
                    subject("Dépense contestée : ", expense.getLabel()), me.getUser(), expense.getCreatedBy()));
        }
        return toResponse(expense, me, true);
    }

    @Transactional
    public ExpenseResponse withdraw(UUID familyId, UUID userId, UUID expenseId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        lockFamily(familyId);
        Expense expense = find(familyId, expenseId);
        requireAuthor(expense, me, "Seul le parent qui a ajouté la dépense peut la retirer.");
        expense.withdraw();
        resolveToDos(expense, clock.instant());
        return toResponse(expense, me, otherParent(familyId, me).isPresent());
    }

    /** Le second parent vient de rejoindre : les dépenses enregistrées en mode solo lui sont proposées. */
    @Transactional
    public void proposeToNewParent(Family family, User newParent) {
        expenseRepository.findByFamilyIdAndStatusInOrderByPaidOnDescCreatedAtDesc(
                        family.getId(), List.of(ExpenseStatus.PENDING)).stream()
                .sorted(Comparator.comparing(Expense::getCreatedAt))
                .forEach(expense -> askAnswer(expense, expense.getCreatedBy(), newParent));
    }

    private void fill(Expense expense, UUID familyId, ExpenseRequest request) {
        if (request.amountCents() <= 0) {
            throw ApiException.badRequest("Indiquez un montant supérieur à 0 €.");
        }
        if (request.amountCents() > MAX_AMOUNT_CENTS) {
            throw ApiException.badRequest("Le montant ne peut pas dépasser 100 000 €.");
        }
        if (!PAYER_SHARES.contains(request.payerSharePercent())) {
            throw ApiException.badRequest("Choisissez une répartition proposée.");
        }
        if (request.paidOn().isAfter(LocalDate.ofInstant(clock.instant(), PARIS))) {
            throw ApiException.badRequest("La date du paiement ne peut pas être dans le futur.");
        }
        Set<UUID> ids = new HashSet<>(request.childIds());
        List<Child> children = childRepository.findAllById(ids);
        if (children.size() != ids.size()
                || children.stream().anyMatch(c -> !c.getFamily().getId().equals(familyId))) {
            throw ApiException.badRequest("Choisissez les enfants de la famille.");
        }
        expense.fill(request.label().strip(), request.amountCents(), request.category(), request.payerRole(),
                request.paidOn(), request.payerSharePercent(), children);
    }

    private void askAnswer(Expense expense, User requester, User assignee) {
        toDoRepository.save(new ToDoItem(expense.getFamily(), ToDoKind.EXPENSE, expense.getId(),
                subject("Dépense : ", expense.getLabel()), requester, assignee));
    }

    private void resolveToDos(Expense expense, Instant now) {
        toDoRepository.findByFamilyIdAndKindAndTargetIdAndResolvedAtIsNull(
                        expense.getFamily().getId(), ToDoKind.EXPENSE, expense.getId())
                .forEach(item -> item.resolve(now));
    }

    private static void requireAuthor(Expense expense, FamilyMember me, String message) {
        if (expense.getAuthorRole() != me.getRole()) {
            throw ApiException.conflict(message);
        }
    }

    private static void requireOther(Expense expense, FamilyMember me) {
        if (expense.getAuthorRole() == me.getRole()) {
            throw ApiException.conflict("Vous ne pouvez pas répondre à votre propre dépense.");
        }
    }

    private Optional<FamilyMember> otherParent(UUID familyId, FamilyMember me) {
        return memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                .filter(m -> m.getRole() != me.getRole())
                .findFirst();
    }

    private Family lockFamily(UUID familyId) {
        return familyRepository.findByIdForUpdate(familyId)
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
    }

    private Expense find(UUID familyId, UUID expenseId) {
        return expenseRepository.findByIdAndFamilyId(expenseId, familyId)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    /** Sujet d'une demande « À traiter » (150 caractères au plus). */
    static String subject(String prefix, String label) {
        String text = prefix + label;
        return text.length() <= 150 ? text : text.substring(0, 149) + "…";
    }

    private static ExpenseResponse toResponse(Expense expense, FamilyMember me, boolean together) {
        boolean mine = expense.getAuthorRole() == me.getRole();
        boolean open = expense.getStatus() == ExpenseStatus.PENDING || expense.getStatus() == ExpenseStatus.CONTESTED;
        User author = expense.getCreatedBy();
        return new ExpenseResponse(
                expense.getId(),
                expense.getStatus(),
                expense.getLabel(),
                expense.getAmountCents(),
                expense.getCategory(),
                expense.getChildren().stream()
                        .sorted(Comparator.comparing(Child::getBirthDate).thenComparing(Child::getFirstName))
                        .map(c -> new ExpenseResponse.ChildRef(c.getId(), c.getFirstName()))
                        .toList(),
                expense.getPayerRole(),
                expense.getPaidOn(),
                expense.getPayerSharePercent(),
                expense.owedCents(),
                expense.getAuthorRole(),
                author == null ? null : author.getFirstName(),
                mine,
                together && !mine && expense.getStatus() == ExpenseStatus.PENDING,
                mine && open,
                expense.getContestReason(),
                expense.getContestNote(),
                expense.getCreatedAt(),
                expense.getAnsweredAt());
    }
}
