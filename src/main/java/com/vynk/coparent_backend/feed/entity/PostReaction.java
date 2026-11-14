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

/** Une réaction d'un parent sur une publication (une de chaque sorte au plus). */
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
    @Enumerated(EnumType.STRING)
    private FamilyRole role;

    @Id
    @Enumerated(EnumType.STRING)
    private ReactionKind kind;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public PostReaction(UUID postId, FamilyRole role, ReactionKind kind) {
        this.postId = postId;
        this.role = role;
        this.kind = kind;
    }

    public record Key(UUID postId, FamilyRole role, ReactionKind kind) implements Serializable {
    }
}
