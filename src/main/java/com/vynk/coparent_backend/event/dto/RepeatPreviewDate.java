package com.vynk.coparent_backend.event.dto;

import com.vynk.coparent_backend.family.entity.FamilyRole;

import java.time.LocalDate;

/**
 * Une date de l'aperçu. {@code parent} : chez qui sont les enfants ce soir-là (null sans rythme) ;
 * {@code skipped} : pourquoi elle n'est pas prévue (null si elle l'est).
 */
public record RepeatPreviewDate(LocalDate date, FamilyRole parent, Skipped skipped) {

    public enum Skipped { HOLIDAYS, OTHER_PARENT }
}
