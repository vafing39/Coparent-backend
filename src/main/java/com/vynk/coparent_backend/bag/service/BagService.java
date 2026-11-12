package com.vynk.coparent_backend.bag.service;

import com.vynk.coparent_backend.bag.dto.BagChildResponse;
import com.vynk.coparent_backend.bag.dto.BagItemRequest;
import com.vynk.coparent_backend.bag.dto.BagItemResponse;
import com.vynk.coparent_backend.bag.dto.BagResponse;
import com.vynk.coparent_backend.bag.entity.BagCheck;
import com.vynk.coparent_backend.bag.entity.BagItem;
import com.vynk.coparent_backend.bag.repository.BagCheckRepository;
import com.vynk.coparent_backend.bag.repository.BagItemRepository;
import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.HandoverResponse;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.service.CustodyService;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sac à préparer pour le prochain passage de garde : une liste durable par enfant, partagée par les deux
 * parents. Une case cochée vaut pour un passage ; après lui, les cases repartent à zéro.
 */
@Service
@RequiredArgsConstructor
public class BagService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final String CHILD_NOT_FOUND = "Enfant introuvable.";
    static final String ITEM_NOT_FOUND = "Élément introuvable.";
    static final String NO_HANDOVER = "Aucun passage de garde n'est prévu.";

    private final FamilyAccess familyAccess;
    private final CustodyService custodyService;
    private final ChildRepository childRepository;
    private final BagItemRepository itemRepository;
    private final BagCheckRepository checkRepository;

    @Transactional(readOnly = true)
    public BagResponse get(UUID familyId, UUID userId) {
        return bag(familyAccess.requireMember(familyId, userId));
    }

    @Transactional
    public BagResponse addItem(UUID familyId, UUID userId, BagItemRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Child child = childRepository.findByIdAndFamilyId(request.childId(), familyId)
                .orElseThrow(() -> ApiException.notFound(CHILD_NOT_FOUND));
        itemRepository.save(new BagItem(me.getFamily(), child, request.label().strip(), me.getUser()));
        return bag(me);
    }

    @Transactional
    public BagResponse deleteItem(UUID familyId, UUID userId, UUID itemId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        itemRepository.delete(requireItem(familyId, itemId)); // ses cases partent avec lui (ON DELETE CASCADE)
        return bag(me);
    }

    @Transactional
    public BagResponse check(UUID familyId, UUID userId, UUID itemId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        BagItem item = requireItem(familyId, itemId);
        Instant handoverAt = requireHandoverAt(me);
        if (!checkRepository.existsByItemIdAndHandoverAt(itemId, handoverAt)) {
            checkRepository.save(new BagCheck(item, handoverAt, me.getUser()));
        }
        return bag(me);
    }

    @Transactional
    public BagResponse uncheck(UUID familyId, UUID userId, UUID itemId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        requireItem(familyId, itemId);
        checkRepository.deleteByItemIdAndHandoverAt(itemId, requireHandoverAt(me));
        return bag(me);
    }

    private BagItem requireItem(UUID familyId, UUID itemId) {
        return itemRepository.findByIdAndFamilyId(itemId, familyId)
                .orElseThrow(() -> ApiException.notFound(ITEM_NOT_FOUND));
    }

    private Instant requireHandoverAt(FamilyMember me) {
        return custodyService.nextHandover(me)
                .map(handover -> toInstant(handover.at()))
                .orElseThrow(() -> ApiException.conflict(NO_HANDOVER));
    }

    private BagResponse bag(FamilyMember me) {
        UUID familyId = me.getFamily().getId();
        LocalDateTime handoverAt = custodyService.nextHandover(me).map(HandoverResponse::at).orElse(null);
        List<BagItem> items = itemRepository.findByFamilyIdOrderByCreatedAtAsc(familyId);

        Map<UUID, BagCheck> checks = handoverAt == null || items.isEmpty()
                ? Map.of()
                : checkRepository.findByItemIdInAndHandoverAt(
                                items.stream().map(BagItem::getId).toList(), toInstant(handoverAt))
                        .stream()
                        .collect(Collectors.toMap(check -> check.getItem().getId(), Function.identity()));

        Map<UUID, List<BagItemResponse>> itemsByChild = items.stream().collect(Collectors.groupingBy(
                item -> item.getChild().getId(),
                Collectors.mapping(item -> itemResponse(item, checks.get(item.getId())), Collectors.toList())));

        List<BagChildResponse> children = childRepository.findByFamilyIdOrderByBirthDateAscCreatedAtAsc(familyId)
                .stream()
                .map(child -> new BagChildResponse(
                        child.getId(), child.getFirstName(), itemsByChild.getOrDefault(child.getId(), List.of())))
                .toList();
        return new BagResponse(handoverAt, checks.size(), items.size(), children);
    }

    private static BagItemResponse itemResponse(BagItem item, BagCheck check) {
        User checkedBy = check == null ? null : check.getCheckedBy();
        return new BagItemResponse(
                item.getId(), item.getLabel(), check != null, checkedBy == null ? null : checkedBy.getFirstName());
    }

    private static Instant toInstant(LocalDateTime parisTime) {
        return parisTime.atZone(PARIS).toInstant();
    }
}
