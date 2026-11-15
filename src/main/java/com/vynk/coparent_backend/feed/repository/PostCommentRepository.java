package com.vynk.coparent_backend.feed.repository;

import com.vynk.coparent_backend.feed.entity.PostComment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostCommentRepository extends JpaRepository<PostComment, UUID> {

    @EntityGraph(attributePaths = {"user", "relative"})
    List<PostComment> findByPostIdOrderByCreatedAtAsc(UUID postId);

    /** Nombre de commentaires par publication : [postId, nombre]. */
    @Query("select c.post.id, count(c) from PostComment c where c.post.id in :postIds group by c.post.id")
    List<Object[]> countByPostIds(@Param("postIds") Collection<UUID> postIds);

    /** Export de données. */
    @EntityGraph(attributePaths = {"user", "post"})
    List<PostComment> findByPostFamilyIdOrderByCreatedAtAsc(UUID familyId);
}
