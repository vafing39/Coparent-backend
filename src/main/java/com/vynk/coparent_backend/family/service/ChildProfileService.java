package com.vynk.coparent_backend.family.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.dto.ChildProfileRequest;
import com.vynk.coparent_backend.family.dto.ChildProfileResponse;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.ChildProfile;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.ChildProfileRepository;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fiche de chaque enfant (onglet Famille › Enfants) : école, santé, contacts utiles. Les deux parents la lisent et
 * la modifient, en solo aussi ; la dernière modification (qui, quand) est affichée.
 */
@Service
@RequiredArgsConstructor
public class ChildProfileService {

    static final int MAX_CONTACTS = 10;
    private static final Pattern PHONE = Pattern.compile("[+0-9 ().-]{6,30}");

    private final FamilyAccess familyAccess;
    private final ChildRepository childRepository;
    private final ChildProfileRepository profileRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ChildProfileResponse get(UUID familyId, UUID childId, UUID userId) {
        familyAccess.requireMember(familyId, userId);
        Child child = requireChild(familyId, childId);
        return profileRepository.findById(child.getId())
                .map(ChildProfileService::toResponse)
                .orElseGet(() -> empty(child.getId()));
    }

    @Transactional
    public ChildProfileResponse save(UUID familyId, UUID childId, UUID userId, ChildProfileRequest request) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Child child = requireChild(familyId, childId);
        String doctorPhone = phone(request.doctorPhone(), "Le numéro du médecin n'est pas valide.");
        List<ChildProfileRequest.Contact> contacts = request.contacts() == null ? List.of() : request.contacts();
        if (contacts.size() > MAX_CONTACTS) {
            throw ApiException.badRequest("Une fiche compte 10 contacts au plus.");
        }
        List<ChildProfile.ContactFields> contactFields = contacts.stream()
                .map(c -> {
                    String name = clean(c.name());
                    if (name == null) {
                        throw ApiException.badRequest("Indiquez le nom de chaque contact.");
                    }
                    return new ChildProfile.ContactFields(name, clean(c.role()),
                            phone(c.phone(), "Le numéro de " + name + " n'est pas valide."));
                })
                .toList();

        ChildProfile profile = profileRepository.findById(child.getId())
                .orElseGet(() -> new ChildProfile(child));
        profile.replace(new ChildProfile.Fields(clean(request.school()), clean(request.grade()),
                        clean(request.teacher()), clean(request.allergies()), clean(request.doctor()), doctorPhone),
                contactFields, me.getUser(), me.getRole(), clock.instant());
        return toResponse(profileRepository.saveAndFlush(profile));
    }

    private Child requireChild(UUID familyId, UUID childId) {
        return childRepository.findByIdAndFamilyId(childId, familyId)
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }

    /** Texte trimé ; vide ou absent → null (champ effacé). */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String phone(String value, String invalid) {
        String phone = clean(value);
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw ApiException.badRequest(invalid);
        }
        return phone;
    }

    private static ChildProfileResponse empty(UUID childId) {
        return new ChildProfileResponse(childId, null, null, null, null, null, null, List.of(), null, null, null);
    }

    private static ChildProfileResponse toResponse(ChildProfile p) {
        return new ChildProfileResponse(p.getChildId(), p.getSchool(), p.getGrade(), p.getTeacher(),
                p.getAllergies(), p.getDoctor(), p.getDoctorPhone(),
                p.getContacts().stream()
                        .map(c -> new ChildProfileResponse.Contact(c.getId(), c.getName(), c.getRole(), c.getPhone()))
                        .toList(),
                p.getUpdatedByRole(), p.getUpdatedBy() == null ? null : p.getUpdatedBy().getFirstName(),
                p.getUpdatedAt());
    }
}
