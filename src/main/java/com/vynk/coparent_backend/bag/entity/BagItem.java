package com.vynk.coparent_backend.bag.entity;

import com.vynk.coparent_backend.family.entity.Child;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Élément du sac d'un enfant (« Kimono »). Il reste d'un passage de garde à l'autre ; seules les cases changent. */
@Entity
@Table(name = "bag_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BagItem {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false, columnDefinition = "binary(16)")
    private Family family;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", nullable = false, columnDefinition = "binary(16)")
    private Child child;

    @Column(nullable = false, length = 100)
    private String label;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", columnDefinition = "binary(16)")
    private User createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public BagItem(Family family, Child child, String label, User createdBy) {
        this.family = family;
        this.child = child;
        this.label = label;
        this.createdBy = createdBy;
    }
}
