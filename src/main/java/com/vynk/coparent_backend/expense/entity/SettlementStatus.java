package com.vynk.coparent_backend.expense.entity;

/** DECLARED et NOT_RECEIVED : le virement attend encore la confirmation de l'autre parent. */
public enum SettlementStatus {
    DECLARED, NOT_RECEIVED, CONFIRMED, CANCELLED;

    public boolean isOpen() {
        return this == DECLARED || this == NOT_RECEIVED;
    }
}
