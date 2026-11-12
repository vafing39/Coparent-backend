package com.vynk.coparent_backend.bag.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sac du prochain passage de garde. {@code handoverAt} : heure de Paris (comme {@code HandoverResponse.at}),
 * null sans passage prévu (rien ne peut alors être coché). {@code children} : chaque enfant, même sans élément.
 */
public record BagResponse(
        LocalDateTime handoverAt,
        int checkedCount,
        int total,
        List<BagChildResponse> children
) {
}
