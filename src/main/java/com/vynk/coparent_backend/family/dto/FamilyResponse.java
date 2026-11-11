package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.entity.VacationZone;

import java.util.List;
import java.util.UUID;

/**
 * La famille vue par l'un de ses membres. {@code solo} : un seul parent pour l'instant.
 * {@code custodySchedule} : le rythme en vigueur, sinon le dernier proposé, sinon null.
 */
public record FamilyResponse(
        UUID id,
        String name,
        VacationZone vacationZone,
        FamilyRole myRole,
        boolean solo,
        List<MemberResponse> members,
        List<ChildResponse> children,
        CustodyScheduleResponse custodySchedule
) {
}
