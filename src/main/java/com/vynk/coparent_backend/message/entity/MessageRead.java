package com.vynk.coparent_backend.message.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Lecture d'un message par son destinataire. Insérée une fois, jamais modifiée. */
@Entity
@Table(name = "message_reads")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageRead {

    @EmbeddedId
    private Key key;

    @Column(nullable = false)
    private Instant readAt;

    @Embeddable
    public record Key(
            @Column(name = "message_id", columnDefinition = "binary(16)") UUID messageId,
            @Column(name = "user_id", columnDefinition = "binary(16)") UUID userId) implements Serializable {
    }
}
