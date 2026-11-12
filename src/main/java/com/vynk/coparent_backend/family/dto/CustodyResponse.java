package com.vynk.coparent_backend.family.dto;

import com.vynk.coparent_backend.family.entity.ScheduleStatus;

import java.util.List;

/**
 * Garde calculée sur une période. {@code scheduleStatus} : ACTIVE (validé par les deux parents) ou PROPOSED
 * (en attendant la validation, ou en mode solo). {@code nextHandover} : premier passage après maintenant,
 * même hors période ; null si le rythme n'en prévoit aucun.
 */
public record CustodyResponse(
        ScheduleStatus scheduleStatus,
        List<CustodyNightResponse> nights,
        List<HandoverResponse> handovers,
        HandoverResponse nextHandover
) {
}
