package com.vynk.coparent_backend.feed.controller;

import com.vynk.coparent_backend.feed.dto.PostCommentRequest;
import com.vynk.coparent_backend.feed.dto.PostCommentResponse;
import com.vynk.coparent_backend.feed.dto.PostPageResponse;
import com.vynk.coparent_backend.feed.dto.PostResponse;
import com.vynk.coparent_backend.feed.dto.RemovalRequest;
import com.vynk.coparent_backend.feed.entity.PostCircle;
import com.vynk.coparent_backend.feed.entity.ReactionKind;
import com.vynk.coparent_backend.feed.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fil de la famille (parents et proches actifs) : publications, photos, réactions, commentaires, retraits. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** Page du fil (20 au plus), la plus récente d'abord ; {@code before} : le {@code nextBefore} de la page précédente. */
    @GetMapping
    public PostPageResponse page(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                 @RequestParam(required = false) Instant before,
                                 @RequestParam(required = false) UUID childId,
                                 @RequestParam(defaultValue = "false") boolean firstTime,
                                 @RequestParam(required = false) Integer limit) {
        return postService.page(familyId, userId(jwt), before, childId, firstTime, limit);
    }

    /**
     * Multipart : {@code body}, {@code childIds} (répété), {@code firstTime}, {@code circle} (PARENTS par défaut ;
     * ignoré pour un proche, toujours FAMILY), {@code photos} (répété, 6 au plus).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @RequestParam(required = false) String body,
                                @RequestParam(required = false) List<UUID> childIds,
                                @RequestParam(defaultValue = "false") boolean firstTime,
                                @RequestParam(required = false) PostCircle circle,
                                @RequestPart(name = "photos", required = false) List<MultipartFile> photos)
            throws IOException {
        List<byte[]> files = new ArrayList<>();
        for (MultipartFile photo : photos == null ? List.<MultipartFile>of() : photos) {
            files.add(photo.getBytes());
        }
        return postService.publish(familyId, userId(jwt), body, childIds, firstTime, circle, files);
    }

    /** Une publication (ouverte depuis « À traiter »). */
    @GetMapping("/{postId}")
    public PostResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId, @PathVariable UUID postId) {
        return postService.get(familyId, userId(jwt), postId);
    }

    /** Une photo : elle ne change jamais, le téléphone peut la garder en cache. */
    @GetMapping("/{postId}/photos/{photoId}")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                        @PathVariable UUID postId, @PathVariable UUID photoId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(postService.photo(familyId, userId(jwt), postId, photoId));
    }

    @PutMapping("/{postId}/reactions/{kind}")
    public PostResponse react(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                              @PathVariable UUID postId, @PathVariable ReactionKind kind) {
        return postService.react(familyId, userId(jwt), postId, kind, true);
    }

    @DeleteMapping("/{postId}/reactions/{kind}")
    public PostResponse unreact(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                @PathVariable UUID postId, @PathVariable ReactionKind kind) {
        return postService.react(familyId, userId(jwt), postId, kind, false);
    }

    @GetMapping("/{postId}/comments")
    public List<PostCommentResponse> comments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                              @PathVariable UUID postId) {
        return postService.comments(familyId, userId(jwt), postId);
    }

    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public PostCommentResponse comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                       @PathVariable UUID postId, @Valid @RequestBody PostCommentRequest request) {
        return postService.comment(familyId, userId(jwt), postId, request.body());
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId, @PathVariable UUID postId) {
        postService.delete(familyId, userId(jwt), postId);
    }

    @PostMapping("/{postId}/removal")
    public PostResponse requestRemoval(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                       @PathVariable UUID postId, @RequestBody(required = false) RemovalRequest request) {
        return postService.requestRemoval(familyId, userId(jwt), postId, request == null ? null : request.reason());
    }

    @DeleteMapping("/{postId}/removal")
    public PostResponse cancelRemoval(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                      @PathVariable UUID postId) {
        return postService.cancelRemoval(familyId, userId(jwt), postId);
    }

    @PostMapping("/{postId}/removal/keep")
    public PostResponse keepHidden(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @PathVariable UUID postId) {
        return postService.keepHidden(familyId, userId(jwt), postId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
