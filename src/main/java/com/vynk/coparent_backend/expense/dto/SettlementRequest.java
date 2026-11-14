package com.vynk.coparent_backend.expense.dto;

import jakarta.validation.constraints.NotNull;

/** Virement déclaré par le parent qui doit le solde, en centimes. */
public record SettlementRequest(@NotNull Integer amountCents) {
}
