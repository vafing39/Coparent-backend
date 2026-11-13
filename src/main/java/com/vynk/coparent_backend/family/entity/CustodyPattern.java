package com.vynk.coparent_backend.family.entity;

/** Rythme de garde. CUSTOM : séquence de nuits définie par les parents. */
public enum CustodyPattern {
    ALTERNATE_WEEKS("une semaine sur deux"),
    TWO_TWO_THREE("2-2-3"),
    TWO_TWO_FIVE_FIVE("2-2-5-5"),
    ALTERNATE_WEEKENDS_WEDNESDAY("week-ends alternés et mercredi"),
    CUSTOM("personnalisé");

    private final String label;

    CustodyPattern(String label) {
        this.label = label;
    }

    /** Libellé en français, ex. dans « Rythme : une semaine sur deux » (À traiter). */
    public String label() {
        return label;
    }
}
