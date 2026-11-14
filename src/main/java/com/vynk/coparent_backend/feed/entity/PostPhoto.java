package com.vynk.coparent_backend.feed.entity;

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

/** Une photo d'une publication, nettoyée (JPEG sans métadonnées). */
@Entity
@Table(name = "post_photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostPhoto {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, columnDefinition = "binary(16)")
    private Post post;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 200)
    private String fileKey;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    PostPhoto(Post post, int position, String fileKey, int width, int height) {
        this.post = post;
        this.position = position;
        this.fileKey = fileKey;
        this.width = width;
        this.height = height;
    }
}
