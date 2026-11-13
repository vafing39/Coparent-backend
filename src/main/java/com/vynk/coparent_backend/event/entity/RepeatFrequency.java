package com.vynk.coparent_backend.event.entity;

/** Fréquence d'un événement répété. */
public enum RepeatFrequency {
    DAILY, WEEKLY, BIWEEKLY, MONTHLY,
    /** Tous les N jours, semaines (jours choisis) ou mois. */
    CUSTOM
}
