package com.vynk.coparent_backend.document.controller;

import com.vynk.coparent_backend.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Fichier d'un document par son lien temporaire, sans session : le lecteur du téléphone l'ouvre directement. Le
 * jeton du lien fait office d'autorisation (signé, 5 minutes).
 */
@RestController
@RequiredArgsConstructor
public class DocumentFileController {

    private final DocumentService documentService;

    @GetMapping(DocumentService.FILES_PATH + "{token}")
    public ResponseEntity<byte[]> file(@PathVariable String token) {
        DocumentService.DocumentFile file = documentService.file(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
