package com.vynk.coparent_backend.exchange.entity;

/** PENDING : attend la réponse de l'autre parent ; ACCEPTED : la garde est recalculée ; DECLINED, CANCELLED : sans effet. */
public enum ExchangeStatus {
    PENDING, ACCEPTED, DECLINED, CANCELLED
}
