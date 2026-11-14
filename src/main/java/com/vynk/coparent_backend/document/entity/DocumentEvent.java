package com.vynk.coparent_backend.document.entity;

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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Une ouverture ou la suppression d'un document, dans son historique. */
@Entity
@Table(name = "document_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentEvent {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, columnDefinition = "binary(16)")
    private Document document;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", columnDefinition = "binary(16)")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentEventKind kind;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public DocumentEvent(Document document, User user, FamilyRole role, DocumentEventKind kind, Instant createdAt) {
        this.document = document;
        this.user = user;
        this.role = role;
        this.kind = kind;
        this.createdAt = createdAt;
    }
}
