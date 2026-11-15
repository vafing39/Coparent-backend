package com.vynk.coparent_backend.feed.repository;

import com.vynk.coparent_backend.feed.entity.PostPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PostPhotoRepository extends JpaRepository<PostPhoto, UUID> {

    /**
     * Album d'un enfant : les photos des publications visibles où il est identifié, la plus récente d'abord. Les
     * publications masquées n'y sont pas ; {@code familyOnly} (un proche) garde seulement le cercle « Famille ».
     */
    @Query("""
            select ph from PostPhoto ph join fetch ph.post p
            where p.family.id = :familyId and p.hiddenAt is null
              and exists (select c from p.children c where c.id = :childId)
              and (:familyOnly = false or p.circle = com.vynk.coparent_backend.feed.entity.PostCircle.FAMILY)
            order by p.createdAt desc, ph.position asc""")
    List<PostPhoto> findAlbum(@Param("familyId") UUID familyId, @Param("childId") UUID childId,
                              @Param("familyOnly") boolean familyOnly);
}
