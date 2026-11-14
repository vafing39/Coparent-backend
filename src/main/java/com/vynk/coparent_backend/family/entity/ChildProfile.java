package com.vynk.coparent_backend.family.entity;

import com.vynk.coparent_backend.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fiche d'un enfant : école, santé (allergies, médecin) et contacts utiles. Modifiable par les deux parents ;
 * garde qui l'a modifiée en dernier, et quand.
 */
@Entity
@Table(name = "child_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildProfile {

    @Id
    @Column(name = "child_id", columnDefinition = "binary(16)")
    private UUID childId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", columnDefinition = "binary(16)")
    private Child child;

    @Column(length = 120)
    private String school;

    @Column(length = 60)
    private String grade;

    @Column(length = 100)
    private String teacher;

    @Column(length = 500)
    private String allergies;

    @Column(length = 120)
    private String doctor;

    @Column(length = 30)
    private String doctorPhone;

    /** Null si le compte a été supprimé. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", columnDefinition = "binary(16)")
    private User updatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FamilyRole updatedByRole;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<ChildContact> contacts = new ArrayList<>();

    public ChildProfile(Child child) {
        this.child = child;
    }

    /** Remplace toute la fiche (contacts compris) et note qui l'a modifiée. */
    public void replace(Fields fields, List<ContactFields> newContacts, User by, FamilyRole role, Instant now) {
        this.school = fields.school();
        this.grade = fields.grade();
        this.teacher = fields.teacher();
        this.allergies = fields.allergies();
        this.doctor = fields.doctor();
        this.doctorPhone = fields.doctorPhone();
        this.contacts.clear();
        for (int i = 0; i < newContacts.size(); i++) {
            ContactFields c = newContacts.get(i);
            this.contacts.add(new ChildContact(this, i, c.name(), c.role(), c.phone()));
        }
        this.updatedBy = by;
        this.updatedByRole = role;
        this.updatedAt = now;
    }

    public record Fields(String school, String grade, String teacher, String allergies, String doctor,
                         String doctorPhone) {
    }

    public record ContactFields(String name, String role, String phone) {
    }
}
