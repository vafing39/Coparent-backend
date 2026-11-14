package com.vynk.coparent_backend.feed.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.common.storage.AfterCommit;
import com.vynk.coparent_backend.common.storage.FileStorage;
import com.vynk.coparent_backend.common.storage.PhotoCleaner;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.family.repository.ChildRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.feed.dto.PostCommentResponse;
import com.vynk.coparent_backend.feed.dto.PostPageResponse;
import com.vynk.coparent_backend.feed.dto.PostResponse;
import com.vynk.coparent_backend.feed.entity.Post;
import com.vynk.coparent_backend.feed.entity.PostComment;
import com.vynk.coparent_backend.feed.entity.PostPhoto;
import com.vynk.coparent_backend.feed.entity.PostReaction;
import com.vynk.coparent_backend.feed.entity.ReactionKind;
import com.vynk.coparent_backend.feed.entity.RemovalReason;
import com.vynk.coparent_backend.feed.repository.PostCommentRepository;
import com.vynk.coparent_backend.feed.repository.PostReactionRepository;
import com.vynk.coparent_backend.feed.repository.PostRepository;
import com.vynk.coparent_backend.todo.entity.ToDoItem;
import com.vynk.coparent_backend.todo.entity.ToDoKind;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fil de la famille, livraison 1 : entre les deux parents (en solo aussi). Une publication porte du texte et/ou des
 * photos nettoyées de leurs métadonnées, les enfants concernés, des réactions (❤️, 👏) et des commentaires ; rien ne
 * se modifie. L'auteur supprime sa publication ; l'autre parent peut la masquer en demandant son retrait : l'auteur
 * l'accepte (suppression) ou la garde masquée, le demandeur peut annuler.
 */
@Service
@RequiredArgsConstructor
public class PostService {

    static final int MAX_PHOTOS = 6;
    static final int MAX_PHOTO_BYTES = 10 * 1024 * 1024;
    static final int BODY_MAX_LENGTH = 2000;
    static final int COMMENT_MAX_LENGTH = 1000;
    static final int PAGE_SIZE = 20;
    static final String NOT_FOUND = "Publication introuvable.";

    private final FamilyAccess familyAccess;
    private final FamilyMemberRepository memberRepository;
    private final ChildRepository childRepository;
    private final PostRepository postRepository;
    private final PostReactionRepository reactionRepository;
    private final PostCommentRepository commentRepository;
    private final ToDoItemRepository toDoRepository;
    private final PhotoCleaner photoCleaner;
    private final FileStorage storage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PostPageResponse page(UUID familyId, UUID userId, Instant before, UUID childId, boolean firstTime,
                                 Integer limit) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        int size = limit == null ? PAGE_SIZE : Math.clamp(limit, 1, PAGE_SIZE);
        List<Post> posts = postRepository.page(familyId, before == null ? clock.instant().plusSeconds(1) : before,
                childId, firstTime, PageRequest.of(0, size));
        List<PostResponse> responses = toResponses(posts, me);
        Instant next = posts.size() == size ? posts.getLast().getCreatedAt() : null;
        return new PostPageResponse(responses, next);
    }

    @Transactional
    public PostResponse publish(UUID familyId, UUID userId, String body, List<UUID> childIds, boolean firstTime,
                                List<byte[]> photos) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        String text = clean(body);
        if (text != null && text.length() > BODY_MAX_LENGTH) {
            throw ApiException.badRequest("Le texte ne peut pas dépasser 2 000 caractères.");
        }
        List<byte[]> files = photos == null ? List.of() : photos.stream().filter(p -> p.length > 0).toList();
        if (text == null && files.isEmpty()) {
            throw ApiException.badRequest("Ajoutez un texte ou une photo.");
        }
        if (files.size() > MAX_PHOTOS) {
            throw ApiException.badRequest("Une publication compte 6 photos au plus.");
        }
        if (files.stream().anyMatch(f -> f.length > MAX_PHOTO_BYTES)) {
            throw ApiException.badRequest("Une photo ne peut pas dépasser 10 Mo.");
        }
        Set<Child> children = new LinkedHashSet<>();
        for (UUID childId : childIds == null ? List.<UUID>of() : childIds) {
            children.add(childRepository.findByIdAndFamilyId(childId, familyId)
                    .orElseThrow(() -> ApiException.badRequest("Cet enfant ne fait pas partie de la famille.")));
        }
        if (firstTime && children.isEmpty()) {
            throw ApiException.badRequest("Une première fois concerne au moins un enfant.");
        }

        // Toutes les photos sont nettoyées avant d'écrire quoi que ce soit.
        List<byte[]> cleaned = files.stream().map(photoCleaner::clean).toList();
        Post post = new Post(me.getFamily(), me.getUser(), me.getRole(), text, firstTime, children);
        List<String> keys = new ArrayList<>();
        try {
            for (byte[] photo : cleaned) {
                String key = "posts/" + familyId + "/" + UUID.randomUUID() + ".jpg";
                storage.put(key, photo);
                keys.add(key);
                BufferedImage image = read(photo);
                post.addPhoto(key, image.getWidth(), image.getHeight());
            }
            Post saved = postRepository.saveAndFlush(post);
            return toResponses(List.of(saved), me).getFirst();
        } catch (RuntimeException e) {
            keys.forEach(storage::delete);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public byte[] photo(UUID familyId, UUID userId, UUID postId, UUID photoId) {
        familyAccess.requireMember(familyId, userId);
        Post post = find(familyId, postId);
        PostPhoto photo = post.getPhotos().stream().filter(p -> p.getId().equals(photoId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Photo introuvable."));
        return storage.get(photo.getFileKey()).orElseThrow(() -> ApiException.notFound("Photo introuvable."));
    }

    @Transactional
    public PostResponse react(UUID familyId, UUID userId, UUID postId, ReactionKind kind, boolean on) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Post post = findVisible(familyId, postId);
        PostReaction.Key key = new PostReaction.Key(post.getId(), me.getRole(), kind);
        if (on && !reactionRepository.existsById(key)) {
            reactionRepository.saveAndFlush(new PostReaction(post.getId(), me.getRole(), kind));
        } else if (!on) {
            reactionRepository.findById(key).ifPresent(reaction -> {
                reactionRepository.delete(reaction);
                reactionRepository.flush();
            });
        }
        return toResponses(List.of(post), me).getFirst();
    }

    @Transactional(readOnly = true)
    public PostResponse get(UUID familyId, UUID userId, UUID postId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        return toResponses(List.of(find(familyId, postId)), me).getFirst();
    }

    @Transactional(readOnly = true)
    public List<PostCommentResponse> comments(UUID familyId, UUID userId, UUID postId) {
        familyAccess.requireMember(familyId, userId);
        Post post = find(familyId, postId);
        return commentRepository.findByPostIdOrderByCreatedAtAsc(post.getId()).stream()
                .map(c -> toResponse(c, familyId))
                .toList();
    }

    @Transactional
    public PostCommentResponse comment(UUID familyId, UUID userId, UUID postId, String body) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Post post = findVisible(familyId, postId);
        String text = clean(body);
        if (text == null) {
            throw ApiException.badRequest("Écrivez votre commentaire.");
        }
        if (text.length() > COMMENT_MAX_LENGTH) {
            throw ApiException.badRequest("Un commentaire ne peut pas dépasser 1 000 caractères.");
        }
        return toResponse(commentRepository.saveAndFlush(new PostComment(post, me.getUser(), me.getRole(), text)),
                familyId);
    }

    /** Par l'auteur : supprime la publication et ses photos (aussi pour accepter une demande de retrait). */
    @Transactional
    public void delete(UUID familyId, UUID userId, UUID postId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Post post = find(familyId, postId);
        if (post.getAuthorRole() != me.getRole()) {
            throw ApiException.conflict("Seul l'auteur peut supprimer cette publication.");
        }
        List<String> keys = post.getPhotos().stream().map(PostPhoto::getFileKey).toList();
        resolveRemovalToDos(post);
        postRepository.delete(post);
        AfterCommit.run(() -> keys.forEach(storage::delete));
    }

    /** Par l'autre parent : masque la publication et demande à l'auteur son accord pour la supprimer. */
    @Transactional
    public PostResponse requestRemoval(UUID familyId, UUID userId, UUID postId, RemovalReason reason) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Post post = find(familyId, postId);
        if (post.getAuthorRole() == me.getRole()) {
            throw ApiException.conflict("Vous pouvez supprimer votre propre publication.");
        }
        if (post.isHidden()) {
            throw ApiException.conflict("Le retrait de cette publication est déjà demandé.");
        }
        post.requestRemoval(me.getRole(), reason == null ? RemovalReason.UNSPECIFIED : reason, clock.instant());
        author(post).ifPresent(author -> toDoRepository.save(new ToDoItem(post.getFamily(),
                ToDoKind.PUBLICATION_REMOVAL, post.getId(), subject(post), me.getUser(), author.getUser())));
        return toResponses(List.of(post), me).getFirst();
    }

    /** Par le demandeur : annule sa demande, la publication réapparaît. */
    @Transactional
    public PostResponse cancelRemoval(UUID familyId, UUID userId, UUID postId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Post post = find(familyId, postId);
        if (!post.isHidden() || post.getRemovalRequestedByRole() != me.getRole()) {
            throw ApiException.conflict("Aucune demande de retrait de votre part sur cette publication.");
        }
        post.cancelRemoval();
        resolveRemovalToDos(post);
        return toResponses(List.of(post), me).getFirst();
    }

    /** Par l'auteur : refuse de supprimer ; la publication reste masquée, la demande est close. */
    @Transactional
    public PostResponse keepHidden(UUID familyId, UUID userId, UUID postId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        Post post = find(familyId, postId);
        if (!post.isHidden() || post.getAuthorRole() != me.getRole()) {
            throw ApiException.conflict("Aucune demande de retrait à traiter sur cette publication.");
        }
        resolveRemovalToDos(post);
        return toResponses(List.of(post), me).getFirst();
    }

    private Post find(UUID familyId, UUID postId) {
        return postRepository.findByIdAndFamilyId(postId, familyId)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    /** Une publication masquée ne reçoit plus de réactions ni de commentaires. */
    private Post findVisible(UUID familyId, UUID postId) {
        Post post = find(familyId, postId);
        if (post.isHidden()) {
            throw ApiException.conflict("Cette publication est masquée.");
        }
        return post;
    }

    private Optional<FamilyMember> author(Post post) {
        return memberRepository.findByFamilyIdOrderByRoleAsc(post.getFamily().getId()).stream()
                .filter(m -> m.getRole() == post.getAuthorRole())
                .findFirst();
    }

    private void resolveRemovalToDos(Post post) {
        Instant now = clock.instant();
        toDoRepository.findByFamilyIdAndKindAndTargetIdAndResolvedAtIsNull(
                        post.getFamily().getId(), ToDoKind.PUBLICATION_REMOVAL, post.getId())
                .forEach(item -> item.resolve(now));
    }

    private static String subject(Post post) {
        String text = post.getBody() == null ? "photo du fil" : post.getBody();
        String full = "Retrait demandé : " + text;
        return full.length() <= 150 ? full : full.substring(0, 149) + "…";
    }

    private List<PostResponse> toResponses(List<Post> posts, FamilyMember me) {
        if (posts.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = posts.stream().map(Post::getId).collect(Collectors.toSet());
        Map<UUID, List<PostReaction>> reactions = reactionRepository.findByPostIdIn(ids).stream()
                .collect(Collectors.groupingBy(PostReaction::getPostId));
        Map<UUID, Long> comments = commentRepository.countByPostIds(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        Map<FamilyRole, String> names = names(me.getFamily().getId());
        return posts.stream().map(post -> {
            boolean mine = post.getAuthorRole() == me.getRole();
            List<PostReaction> own = reactions.getOrDefault(post.getId(), List.of());
            PostResponse.Removal removal = post.isHidden()
                    ? new PostResponse.Removal(post.getRemovalRequestedByRole(), post.getRemovalReason(),
                    post.getHiddenAt(), post.getRemovalRequestedByRole() == me.getRole())
                    : null;
            return new PostResponse(post.getId(), post.getAuthorRole(),
                    post.getCreatedBy() != null ? post.getCreatedBy().getFirstName() : names.get(post.getAuthorRole()),
                    post.getCreatedAt(), post.getBody(), post.isFirstTime(),
                    post.getChildren().stream()
                            .map(c -> new PostResponse.Child(c.getId(), c.getFirstName()))
                            .toList(),
                    post.getPhotos().stream()
                            .map(p -> new PostResponse.Photo(p.getId(), p.getWidth(), p.getHeight()))
                            .toList(),
                    Arrays.stream(ReactionKind.values())
                            .map(kind -> new PostResponse.Reaction(kind,
                                    (int) own.stream().filter(r -> r.getKind() == kind).count(),
                                    own.stream().anyMatch(r -> r.getKind() == kind && r.getRole() == me.getRole())))
                            .toList(),
                    comments.getOrDefault(post.getId(), 0L).intValue(),
                    removal, mine, !mine && !post.isHidden());
        }).toList();
    }

    private PostCommentResponse toResponse(PostComment comment, UUID familyId) {
        String name = comment.getUser() != null
                ? comment.getUser().getFirstName()
                : names(familyId).get(comment.getRole());
        return new PostCommentResponse(comment.getId(), comment.getRole(), name, comment.getBody(),
                comment.getCreatedAt());
    }

    /** Prénom actuel de chaque parent (pour un compte supprimé : le parent qui a repris le rôle, sinon null). */
    private Map<FamilyRole, String> names(UUID familyId) {
        return memberRepository.findByFamilyIdOrderByRoleAsc(familyId).stream()
                .collect(Collectors.toMap(FamilyMember::getRole, m -> m.getUser().getFirstName(), (a, b) -> a));
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BufferedImage read(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
