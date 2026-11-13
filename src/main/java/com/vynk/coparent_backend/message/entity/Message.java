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

/**
 * Message entre parents. Immuable : aucun setter, {@code @Immutable} côté Hibernate, et des triggers en base
 * qui refusent toute modification ou suppression. {@code hash} chaîne le message au précédent de la famille.
 */
@Entity
@Table(name = "messages")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

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
    @JoinColumn(name = "sender_id", columnDefinition = "binary(16)")
    private User sender;

    @Column(nullable = false)
    private long seq;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64, columnDefinition = "char(64)")
    private String prevHash;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String hash;

    public Message(Family family, User sender, long seq, String body, Instant createdAt, String prevHash,
                   String hash) {
        this.family = family;
        this.sender = sender;
        this.seq = seq;
        this.body = body;
        this.createdAt = createdAt;
        this.prevHash = prevHash;
        this.hash = hash;
    }
}
