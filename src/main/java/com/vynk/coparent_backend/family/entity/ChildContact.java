package com.vynk.coparent_backend.family.entity;

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
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** Un contact utile d'un enfant (nounou, garderie, club…). */
@Entity
@Table(name = "child_contacts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildContact {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", nullable = false, columnDefinition = "binary(16)")
    private ChildProfile profile;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String role;

    @Column(length = 30)
    private String phone;

    ChildContact(ChildProfile profile, int position, String name, String role, String phone) {
        this.profile = profile;
        this.position = position;
        this.name = name;
        this.role = role;
        this.phone = phone;
    }
}
