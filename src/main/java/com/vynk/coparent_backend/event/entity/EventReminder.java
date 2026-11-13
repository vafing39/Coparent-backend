package com.vynk.coparent_backend.event.entity;

/** Rappel avant l'événement (enregistré ; l'envoi viendra avec les notifications). */
public enum EventReminder {
    NONE,
    ONE_HOUR,
    DAY_BEFORE,
    TWO_DAYS
}
