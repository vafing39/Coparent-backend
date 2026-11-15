package com.vynk.coparent_backend.feed.entity;

import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyRelative;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Une publication du fil : texte et/ou photos, les enfants concernés, son cercle (« Partagé » ou « Famille »), par
 * un parent ou un proche. Ne se modifie pas. Masquée quand l'autre parent
 * demande son retrait ({@code hiddenAt}), jusqu'à ce que l'auteur la supprime ou que la demande soit annulée.
 */
@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostCircle circle;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", columnDefinition = "binary(16)")
    private User createdBy;

    /** Le parent auteur ; null pour un proche. */
    @Enumerated(EnumType.STRING)
    private FamilyRole authorRole;

    /** Le proche auteur ; null pour un parent. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_relative_id", columnDefinition = "binary(16)")
    private FamilyRelative authorRelative;

    @Column(length = 2000)
    private String body;

    @Column(nullable = false)
    private boolean firstTime;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant hiddenAt;

    @Enumerated(EnumType.STRING)
    private FamilyRole removalRequestedByRole;

    @Enumerated(EnumType.STRING)
    private RemovalReason removalReason;

    @BatchSize(size = 20)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "post_children",
            joinColumns = @JoinColumn(name = "post_id", columnDefinition = "binary(16)"),
            inverseJoinColumns = @JoinColumn(name = "child_id", columnDefinition = "binary(16)"))
    private Set<Child> children = new LinkedHashSet<>();

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<PostPhoto> photos = new ArrayList<>();

    /** {@code authorRole} pour un parent, {@code authorRelative} pour un proche (l'autre est null). */
    public Post(Family family, PostCircle circle, User createdBy, FamilyRole authorRole,
                FamilyRelative authorRelative, String body, boolean firstTime, Set<Child> children) {
        this.family = family;
        this.circle = circle;
        this.createdBy = createdBy;
        this.authorRole = authorRole;
        this.authorRelative = authorRelative;
        this.body = body;
        this.firstTime = firstTime;
        this.children = new LinkedHashSet<>(children);
    }

    public void addPhoto(String fileKey, int width, int height) {
        photos.add(new PostPhoto(this, photos.size(), fileKey, width, height));
    }

    public boolean isHidden() {
        return hiddenAt != null;
    }

    public void requestRemoval(FamilyRole by, RemovalReason reason, Instant now) {
        this.hiddenAt = now;
        this.removalRequestedByRole = by;
        this.removalReason = reason;
    }

    public void cancelRemoval() {
        this.hiddenAt = null;
        this.removalRequestedByRole = null;
        this.removalReason = null;
    }
}
