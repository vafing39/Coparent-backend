package com.vynk.coparent_backend.feed.entity;

import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Un commentaire sur une publication : publié tout de suite, jamais modifié. */
@Entity
@Table(name = "post_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostComment {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, columnDefinition = "binary(16)")
    private Post post;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "binary(16)")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole role;

    @Column(nullable = false, length = 1000)
    private String body;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public PostComment(Post post, User user, FamilyRole role, String body) {
        this.post = post;
        this.user = user;
        this.role = role;
        this.body = body;
    }
}
