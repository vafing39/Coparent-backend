package com.vynk.coparent_backend.message.entity;

import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Discussion sur un sujet entre les deux parents. Le sujet ne change pas ; elle ne se supprime pas. */
@Entity
@Table(name = "conversations")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", columnDefinition = "binary(16)")
    private User author;

    @Column(nullable = false, length = 80)
    private String subject;

    @Column(nullable = false)
    private Instant createdAt;

    public Conversation(Family family, User author, String subject, Instant createdAt) {
        this.family = family;
        this.author = author;
        this.subject = subject;
        this.createdAt = createdAt;
    }
}
