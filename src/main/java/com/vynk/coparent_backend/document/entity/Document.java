package com.vynk.coparent_backend.document.entity;

import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.Family;
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

/**
 * Un document partagé de la famille (PDF ou photo nettoyée). Supprimé par son auteur, la ligne reste (avec son
 * historique) mais le fichier disparaît.
 */
@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

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
    @JoinColumn(name = "created_by", columnDefinition = "binary(16)")
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole authorRole;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentCategory category;

    /** Null : tous les enfants. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_id", columnDefinition = "binary(16)")
    private Child child;

    @Column(length = 200)
    private String fileKey;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private int sizeBytes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;

    @Enumerated(EnumType.STRING)
    private FamilyRole deletedByRole;

    public Document(Family family, User createdBy, FamilyRole authorRole, String title,
                    DocumentCategory category, Child child, String fileKey, String contentType, int sizeBytes) {
        this.family = family;
        this.createdBy = createdBy;
        this.authorRole = authorRole;
        this.title = title;
        this.category = category;
        this.child = child;
        this.fileKey = fileKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Renvoie la clé du fichier, à supprimer du stockage. */
    public String delete(FamilyRole by, Instant now) {
        String key = fileKey;
        this.fileKey = null;
        this.deletedAt = now;
        this.deletedByRole = by;
        return key;
    }
}
