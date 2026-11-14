package com.vynk.coparent_backend.expense.dto;

import com.vynk.coparent_backend.expense.entity.ContestReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contestation : un motif et, en option, une précision pour l'autre parent. */
public record ContestRequest(@NotNull ContestReason reason, @Size(max = 500) String note) {
}
