package com.vynk.coparent_backend.family.entity;

/** Cycle de vie d'une version du rythme. Au plus une version ACTIVE par famille (contrainte en base). */
public enum ScheduleStatus {
    PROPOSED, ACTIVE, REPLACED, REJECTED
}
