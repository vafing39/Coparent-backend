package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.ChildRequest;
import com.vynk.coparent_backend.family.dto.ChildResponse;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.mapper.FamilyMapper;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Étape 3 : les enfants de la famille. */
@Service
@RequiredArgsConstructor
public class ChildService {

    private final ChildRepository childRepository;
    private final FamilyAccess familyAccess;
    private final FamilyMapper mapper;

    @Transactional
    public ChildResponse add(UUID familyId, UUID userId, ChildRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Child child = childRepository.save(
                new Child(me.getFamily(), request.firstName().trim(), request.birthDate()));
        return mapper.toResponse(child);
    }

    @Transactional
    public ChildResponse update(UUID familyId, UUID childId, UUID userId, ChildRequest request) {
        familyAccess.requireMember(familyId, userId);
        Child child = requireChild(familyId, childId);
        child.update(request.firstName().trim(), request.birthDate());
        return mapper.toResponse(child);
    }

    @Transactional
    public void delete(UUID familyId, UUID childId, UUID userId) {
        familyAccess.requireMember(familyId, userId);
        childRepository.delete(requireChild(familyId, childId));
    }

    private Child requireChild(UUID familyId, UUID childId) {
        return childRepository.findByIdAndFamilyId(childId, familyId)
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }
}
