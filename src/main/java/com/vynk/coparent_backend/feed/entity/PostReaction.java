package com.vynk.coparent_backend.feed.entity;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Une réaction sur une publication (une de chaque sorte par personne). {@code reactor} : le rôle du parent
 * ({@code PARENT_A}) ou l'id du proche.
 */
@Entity
@Table(name = "post_reactions")
@IdClass(PostReaction.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostReaction {

    @Id
    @Column(name = "post_id", columnDefinition = "binary(16)")
    private UUID postId;

    @Id
    @Column(length = 36)
    private String reactor;

    @Id
    @Enumerated(EnumType.STRING)
    private ReactionKind kind;

    /** Le parent ; null pour un proche. */
    @Enumerated(EnumType.STRING)
    private FamilyRole role;

    /** Le proche ; null pour un parent. */
    @Column(name = "relative_id", columnDefinition = "binary(16)")
    private UUID relativeId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public PostReaction(UUID postId, String reactor, ReactionKind kind, FamilyRole role, UUID relativeId) {
        this.postId = postId;
        this.reactor = reactor;
        this.kind = kind;
        this.role = role;
        this.relativeId = relativeId;
    }

    public record Key(UUID postId, String reactor, ReactionKind kind) implements Serializable {
    }
}
