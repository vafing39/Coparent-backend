package com.vynk.coparent_backend.family.entity;

/** Lien de parenté d'un proche avec les enfants. */
public enum RelativeRelation {
    GRANDPARENT, STEP_PARENT, UNCLE_AUNT;

    /** « grand-parent », pour l'email d'invitation. */
    public String label() {
        return switch (this) {
            case GRANDPARENT -> "grand-parent";
            case STEP_PARENT -> "beau-parent";
            case UNCLE_AUNT -> "oncle ou tante";
        };
    }
}
