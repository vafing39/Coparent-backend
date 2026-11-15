package com.vynk.coparent_backend.family.entity;

/**
 * PROPOSED : proposé par un parent, en attente de l'autre ; INVITED : validé par les deux, lien envoyé ; ACTIVE : a
 * rejoint. Fins : DECLINED (l'autre parent refuse), CANCELLED (un parent annule avant l'arrivée), REMOVED (un parent
 * le retire), LEFT (il quitte la famille ou supprime son compte).
 */
public enum RelativeStatus {
    PROPOSED, INVITED, ACTIVE, DECLINED, CANCELLED, REMOVED, LEFT;

    public boolean isOpen() {
        return this == PROPOSED || this == INVITED || this == ACTIVE;
    }
}
