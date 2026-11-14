package com.vynk.coparent_backend.expense.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.expense.dto.BalanceResponse;
import com.vynk.coparent_backend.expense.dto.SettlementRequest;
import com.vynk.coparent_backend.expense.dto.SettlementResponse;
import com.vynk.coparent_backend.expense.entity.Expense;
import com.vynk.coparent_backend.expense.entity.ExpenseStatus;
import com.vynk.coparent_backend.expense.entity.Settlement;
import com.vynk.coparent_backend.expense.entity.SettlementStatus;
import com.vynk.coparent_backend.expense.repository.ExpenseRepository;
import com.vynk.coparent_backend.expense.repository.SettlementRepository;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.todo.entity.ToDoItem;
import com.vynk.coparent_backend.todo.entity.ToDoKind;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Le solde courant entre les parents et ses règlements. Le parent qui doit le solde déclare un virement ; l'autre
 * en confirme la réception depuis « À traiter », ou indique qu'il ne l'a pas encore reçu. Un seul virement ouvert à
 * la fois. L'app n'effectue aucun paiement.
 */
@Service
@RequiredArgsConstructor
public class BalanceService {

    static final String SOLO = "Le solde sera calculé quand l'autre parent aura rejoint.";
    private static final List<SettlementStatus> OPEN = List.of(SettlementStatus.DECLARED, SettlementStatus.NOT_RECEIVED);

    private final FamilyAccess familyAccess;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final ExpenseRepository expenseRepository;
    private final SettlementRepository settlementRepository;
    private final ToDoItemRepository toDoRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public BalanceResponse balance(UUID familyId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        List<Expense> expenses = validatedExpenses(familyId);
        List<Settlement> confirmed = settlementRepository.findByFamilyIdAndStatusInOrderByDeclaredAtDesc(
                familyId, List.of(SettlementStatus.CONFIRMED));
        long owedByA = BalanceCalculator.owedByParentA(expenses, confirmed);

        List<BalanceResponse.Line> lines = Stream.concat(
                        expenses.stream().map(e -> new BalanceResponse.Line(BalanceResponse.Kind.EXPENSE, e.getId(),
                                e.getLabel(), e.getAmountCents(), e.getPayerRole(), e.getPayerSharePercent(),
                                e.owedCents(), e.getPaidOn())),
                        confirmed.stream().map(s -> new BalanceResponse.Line(BalanceResponse.Kind.SETTLEMENT,
                                s.getId(), null, s.getAmountCents(), s.getPayerRole(), null, s.getAmountCents(),
                                LocalDate.ofInstant(s.getAnsweredAt(), ExpenseService.PARIS))))
                .sorted(Comparator.comparing(BalanceResponse.Line::date).reversed())
                .toList();
        SettlementResponse open = openSettlement(familyId).map(s -> toResponse(s, me)).orElse(null);

        return new BalanceResponse(
                owedByA == 0 ? null : owedByA > 0 ? FamilyRole.PARENT_A : FamilyRole.PARENT_B,
                Math.toIntExact(Math.abs(owedByA)),
                lines,
                open);
    }

    /** Déclaré par le parent qui doit le solde, d'un montant au plus égal au solde. */
    @Transactional
    public SettlementResponse declare(UUID familyId, UUID userId, SettlementRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Family family = familyRepository.findByIdForUpdate(familyId)
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
        FamilyMember other = memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                .filter(m -> m.getRole() != me.getRole())
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(SOLO));
        if (openSettlement(familyId).isPresent()) {
            throw ApiException.conflict("Un virement attend déjà une confirmation.");
        }
        long owedByA = BalanceCalculator.owedByParentA(validatedExpenses(familyId),
                settlementRepository.findByFamilyIdAndStatusInOrderByDeclaredAtDesc(
                        familyId, List.of(SettlementStatus.CONFIRMED)));
        long iOwe = me.getRole() == FamilyRole.PARENT_A ? owedByA : -owedByA;
        if (iOwe <= 0) {
            throw ApiException.conflict("Vous ne devez rien à " + other.getUser().getFirstName() + ".");
        }
        if (request.amountCents() <= 0 || request.amountCents() > iOwe) {
            throw ApiException.badRequest("Le virement va de 0,01 € à " + euros(iOwe) + ".");
        }

        Settlement settlement = settlementRepository.save(new Settlement(family, me.getRole(), request.amountCents()));
        toDoRepository.save(new ToDoItem(family, ToDoKind.SETTLEMENT, settlement.getId(),
                "Virement de " + euros(request.amountCents()) + " à confirmer", me.getUser(), other.getUser()));
        return toResponse(settlement, me);
    }

    @Transactional
    public SettlementResponse confirm(UUID familyId, UUID userId, UUID settlementId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Settlement settlement = findForAnswer(familyId, settlementId, me);
        Instant now = clock.instant();
        settlement.confirm(now);
        resolveToDo(familyId, settlementId, now);
        return toResponse(settlement, me);
    }

    /** Le virement reste ouvert (et dans « À traiter ») : il pourra être confirmé dès réception. */
    @Transactional
    public SettlementResponse notReceived(UUID familyId, UUID userId, UUID settlementId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Settlement settlement = findForAnswer(familyId, settlementId, me);
        settlement.notReceived(clock.instant());
        return toResponse(settlement, me);
    }

    @Transactional
    public SettlementResponse cancel(UUID familyId, UUID userId, UUID settlementId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        familyRepository.findByIdForUpdate(familyId);
        Settlement settlement = find(familyId, settlementId);
        if (settlement.getPayerRole() != me.getRole()) {
            throw ApiException.conflict("Seul le parent qui a déclaré le virement peut l'annuler.");
        }
        Instant now = clock.instant();
        settlement.cancel(now);
        resolveToDo(familyId, settlementId, now);
        return toResponse(settlement, me);
    }

    private Settlement findForAnswer(UUID familyId, UUID settlementId, FamilyMember me) {
        familyRepository.findByIdForUpdate(familyId);
        Settlement settlement = find(familyId, settlementId);
        if (settlement.getPayerRole() == me.getRole()) {
            throw ApiException.conflict("C'est à l'autre parent de confirmer la réception.");
        }
        return settlement;
    }

    private List<Expense> validatedExpenses(UUID familyId) {
        return expenseRepository.findByFamilyIdAndStatusInOrderByPaidOnDescCreatedAtDesc(
                familyId, List.of(ExpenseStatus.VALIDATED));
    }

    private Optional<Settlement> openSettlement(UUID familyId) {
        return settlementRepository.findByFamilyIdAndStatusInOrderByDeclaredAtDesc(familyId, OPEN).stream().findFirst();
    }

    private void resolveToDo(UUID familyId, UUID settlementId, Instant now) {
        toDoRepository.findByFamilyIdAndKindAndTargetIdAndResolvedAtIsNull(familyId, ToDoKind.SETTLEMENT, settlementId)
                .forEach(item -> item.resolve(now));
    }

    private Settlement find(UUID familyId, UUID settlementId) {
        return settlementRepository.findByIdAndFamilyId(settlementId, familyId)
                .orElseThrow(() -> ApiException.notFound("Virement introuvable."));
    }

    /** « 42,50 € ». */
    static String euros(long cents) {
        return (cents / 100) + "," + String.format("%02d", cents % 100) + " €";
    }

    private static SettlementResponse toResponse(Settlement settlement, FamilyMember me) {
        return new SettlementResponse(settlement.getId(), settlement.getPayerRole(), settlement.getAmountCents(),
                settlement.getStatus(), settlement.getPayerRole() == me.getRole(), settlement.getDeclaredAt(),
                settlement.getAnsweredAt());
    }
}
