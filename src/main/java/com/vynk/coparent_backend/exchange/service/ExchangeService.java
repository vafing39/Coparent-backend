package com.vynk.coparent_backend.exchange.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.exchange.dto.ExchangeRequest;
import com.vynk.coparent_backend.exchange.dto.ExchangeResponse;
import com.vynk.coparent_backend.exchange.entity.CustodyExchange;
import com.vynk.coparent_backend.exchange.entity.ExchangeStatus;
import com.vynk.coparent_backend.exchange.repository.CustodyExchangeRepository;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.family.service.CustodyCalculator;
import com.vynk.coparent_backend.family.service.CustodyService;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Demandes d'échange de garde (cahier des charges, 6). Un parent prend des nuits de l'autre et, en option, lui en
 * laisse en retour. L'autre parent accepte ou refuse (« proposer une autre date » passe par la messagerie) ; le
 * demandeur peut annuler tant qu'il n'y a pas de réponse. Chaque demande en attente apparaît dans « À traiter »
 * de l'autre parent. Acceptée, elle change le calcul de la garde pour les deux ({@link CustodyService}).
 */
@Service
@RequiredArgsConstructor
public class ExchangeService {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final int MAX_NIGHTS = 31;
    static final String SOLO = "L'échange de garde s'ouvrira quand l'autre parent aura rejoint.";
    static final String NOT_FOUND = "Échange introuvable.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH);

    private final FamilyAccess familyAccess;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final CustodyExchangeRepository exchangeRepository;
    private final ToDoItemRepository toDoRepository;
    private final CustodyService custodyService;
    private final Clock clock;

    /** Demandes en attente et échanges acceptés pas encore passés. */
    @Transactional(readOnly = true)
    public List<ExchangeResponse> list(UUID familyId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        LocalDate today = today();
        return exchangeRepository
                .findByFamilyIdAndStatusInOrderByTakeFromAsc(familyId, List.of(ExchangeStatus.PENDING, ExchangeStatus.ACCEPTED))
                .stream()
                .filter(e -> e.getStatus() == ExchangeStatus.PENDING || !e.lastNight().isBefore(today))
                .map(e -> toResponse(e, me))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExchangeResponse get(UUID familyId, UUID userId, UUID exchangeId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        return toResponse(find(familyId, exchangeId), me);
    }

    @Transactional
    public ExchangeResponse create(UUID familyId, UUID userId, ExchangeRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Family family = familyRepository.findByIdForUpdate(familyId)
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
        FamilyMember other = memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                .filter(m -> m.getRole() != me.getRole())
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(SOLO));

        validateDates(request);
        CustodyCalculator calculator = custodyService.currentCalculator(me)
                .orElseThrow(() -> ApiException.conflict("Aucun rythme de garde n'est encore défini."));
        forEachNight(request.takeFrom(), request.takeTo(), night -> {
            if (calculator.parentOn(night) != other.getRole()) {
                throw ApiException.badRequest(
                        "Les nuits que vous prenez doivent être chez " + other.getUser().getFirstName() + ".");
            }
        });
        if (request.giveFrom() != null) {
            forEachNight(request.giveFrom(), request.giveTo(), night -> {
                if (calculator.parentOn(night) != me.getRole()) {
                    throw ApiException.badRequest("Les nuits que vous laissez doivent être chez vous.");
                }
            });
        }
        boolean taken = exchangeRepository
                .findByFamilyIdAndStatusInOrderByTakeFromAsc(familyId, List.of(ExchangeStatus.PENDING, ExchangeStatus.ACCEPTED))
                .stream()
                .anyMatch(e -> e.overlaps(request.takeFrom(), request.takeTo())
                        || (request.giveFrom() != null && e.overlaps(request.giveFrom(), request.giveTo())));
        if (taken) {
            throw ApiException.conflict("Certaines de ces nuits font déjà l'objet d'un échange.");
        }

        String message = request.message() == null || request.message().isBlank() ? null : request.message().strip();
        CustodyExchange exchange = exchangeRepository.save(new CustodyExchange(family, me.getUser(), me.getRole(),
                request.takeFrom(), request.takeTo(), request.giveFrom(), request.giveTo(), message));
        toDoRepository.save(new ToDoItem(family, ToDoKind.EXCHANGE, exchange.getId(),
                "Échange : " + period(request.takeFrom(), request.takeTo()), me.getUser(), other.getUser()));
        return toResponse(exchange, me);
    }

    @Transactional
    public ExchangeResponse accept(UUID familyId, UUID userId, UUID exchangeId) {
        return answer(familyId, userId, exchangeId, true);
    }

    @Transactional
    public ExchangeResponse decline(UUID familyId, UUID userId, UUID exchangeId) {
        return answer(familyId, userId, exchangeId, false);
    }

    @Transactional
    public ExchangeResponse cancel(UUID familyId, UUID userId, UUID exchangeId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        familyRepository.findByIdForUpdate(familyId);
        CustodyExchange exchange = find(familyId, exchangeId);
        if (exchange.getRequesterRole() != me.getRole()) {
            throw ApiException.conflict("Seul le parent qui a fait la demande peut l'annuler.");
        }
        Instant now = clock.instant();
        exchange.cancel(now);
        resolveToDo(familyId, exchangeId, now);
        return toResponse(exchange, me);
    }

    private ExchangeResponse answer(UUID familyId, UUID userId, UUID exchangeId, boolean accepted) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        familyRepository.findByIdForUpdate(familyId);
        CustodyExchange exchange = find(familyId, exchangeId);
        if (exchange.getRequesterRole() == me.getRole()) {
            throw ApiException.conflict("Vous ne pouvez pas répondre à votre propre demande.");
        }
        Instant now = clock.instant();
        if (accepted) {
            exchange.accept(now);
        } else {
            exchange.decline(now);
        }
        resolveToDo(familyId, exchangeId, now);
        return toResponse(exchange, me);
    }

    private void validateDates(ExchangeRequest request) {
        LocalDate tomorrow = today().plusDays(1);
        checkPeriod(request.takeFrom(), request.takeTo(), tomorrow);
        if ((request.giveFrom() == null) != (request.giveTo() == null)) {
            throw ApiException.badRequest("Indiquez le début et la fin des nuits que vous laissez.");
        }
        if (request.giveFrom() != null) {
            checkPeriod(request.giveFrom(), request.giveTo(), tomorrow);
            if (!request.giveFrom().isAfter(request.takeTo()) && !request.takeFrom().isAfter(request.giveTo())) {
                throw ApiException.badRequest("Les deux périodes ne peuvent pas se chevaucher.");
            }
        }
    }

    private static void checkPeriod(LocalDate from, LocalDate to, LocalDate tomorrow) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("La dernière nuit suit la première.");
        }
        if (from.isBefore(tomorrow)) {
            throw ApiException.badRequest("Un échange porte sur des nuits à venir, à partir de demain.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_NIGHTS) {
            throw ApiException.badRequest(MAX_NIGHTS + " nuits au plus par période.");
        }
    }

    private static void forEachNight(LocalDate from, LocalDate to, Consumer<LocalDate> check) {
        for (LocalDate night = from; !night.isAfter(to); night = night.plusDays(1)) {
            check.accept(night);
        }
    }

    private void resolveToDo(UUID familyId, UUID exchangeId, Instant now) {
        toDoRepository.findByFamilyIdAndKindAndTargetIdAndResolvedAtIsNull(familyId, ToDoKind.EXCHANGE, exchangeId)
                .forEach(item -> item.resolve(now));
    }

    private CustodyExchange find(UUID familyId, UUID exchangeId) {
        return exchangeRepository.findByIdAndFamilyId(exchangeId, familyId)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), PARIS);
    }

    /** « du ven. 20 nov. au dim. 22 nov. », ou « nuit du ven. 20 nov. ». */
    static String period(LocalDate from, LocalDate to) {
        return from.equals(to)
                ? "nuit du " + DAY.format(from)
                : "du " + DAY.format(from) + " au " + DAY.format(to);
    }

    private static ExchangeResponse toResponse(CustodyExchange exchange, FamilyMember me) {
        User requestedBy = exchange.getRequestedBy();
        return new ExchangeResponse(
                exchange.getId(),
                exchange.getStatus(),
                exchange.getRequesterRole(),
                requestedBy == null ? null : requestedBy.getFirstName(),
                exchange.getRequesterRole() == me.getRole(),
                exchange.getTakeFrom(),
                exchange.getTakeTo(),
                exchange.getGiveFrom(),
                exchange.getGiveTo(),
                exchange.getMessage(),
                exchange.getCreatedAt(),
                exchange.getAnsweredAt());
    }
}
