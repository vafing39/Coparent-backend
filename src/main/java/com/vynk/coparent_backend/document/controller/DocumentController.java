package com.vynk.coparent_backend.document.controller;

import com.vynk.coparent_backend.document.dto.DocumentLinkResponse;
import com.vynk.coparent_backend.document.dto.DocumentResponse;
import com.vynk.coparent_backend.document.entity.DocumentCategory;
import com.vynk.coparent_backend.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Documents partagés de la famille, ouverts par un lien temporaire. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping
    public List<DocumentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return documentService.list(familyId, userId(jwt));
    }

    /** Multipart : {@code file} (PDF, JPEG ou PNG, 20 Mo au plus), {@code title}, {@code category}, {@code childId}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @RequestPart("file") MultipartFile file, @RequestParam String title,
                                   @RequestParam DocumentCategory category,
                                   @RequestParam(required = false) UUID childId) throws IOException {
        return documentService.upload(familyId, userId(jwt), file.getBytes(), title, category, childId);
    }

    @PostMapping("/{documentId}/open")
    public DocumentLinkResponse open(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                     @PathVariable UUID documentId) {
        return documentService.open(familyId, userId(jwt), documentId);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId, @PathVariable UUID documentId) {
        documentService.delete(familyId, userId(jwt), documentId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
