package com.vynk.coparent_backend.event.entity;

/** Séances d'une série concernées par une modification ou une suppression. */
public enum EventScope {
    /** Cette séance seulement. */
    ONE,
    /** Cette séance et les suivantes. */
    FOLLOWING,
    /** Toutes les séances à venir (les séances passées ne changent jamais). */
    ALL
}
