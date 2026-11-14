package com.vynk.coparent_backend.expense.entity;

/** PENDING : attend la réponse de l'autre parent. Seules les dépenses VALIDATED comptent dans le solde. */
public enum ExpenseStatus {
    PENDING, CONTESTED, VALIDATED, WITHDRAWN
}
