package com.vynk.coparent_backend.feed.entity;

/** Raison (facultative) d'une demande de retrait. */
public enum RemovalReason {
    /** Photo trop personnelle. */
    PERSONAL,
    /** On voit l'école ou le lieu de vie. */
    PLACE,
    /** Je préfère ne pas préciser. */
    UNSPECIFIED
}
