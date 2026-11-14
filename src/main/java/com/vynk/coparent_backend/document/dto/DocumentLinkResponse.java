package com.vynk.coparent_backend.document.dto;

import java.time.Instant;

/**
 * Lien temporaire vers le fichier d'un document, à ouvrir sans session. {@code path} est relatif
 * ({@code /api/v1/document-files/{token}}) : l'app le préfixe avec l'adresse du backend qu'elle utilise déjà.
 */
public record DocumentLinkResponse(String path, Instant expiresAt) {
}
