package com.vynk.coparent_backend.bag.entity;

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

/**
 * Case cochée d'un élément pour un passage de garde ({@code handoverAt}, instant UTC). Une case d'un autre
 * passage ne compte pas : les cases se remettent ainsi à zéro après chaque passage.
 */
@Entity
@Table(name = "bag_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BagCheck {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, columnDefinition = "binary(16)")
    private BagItem item;

    @Column(nullable = false)
    private Instant handoverAt;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_by", columnDefinition = "binary(16)")
    private User checkedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant checkedAt;

    public BagCheck(BagItem item, Instant handoverAt, User checkedBy) {
        this.item = item;
        this.handoverAt = handoverAt;
        this.checkedBy = checkedBy;
    }
}
