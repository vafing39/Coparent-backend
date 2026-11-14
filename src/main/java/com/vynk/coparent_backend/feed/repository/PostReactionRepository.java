package com.vynk.coparent_backend.feed.repository;

import com.vynk.coparent_backend.feed.entity.PostReaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostReactionRepository extends JpaRepository<PostReaction, PostReaction.Key> {

    List<PostReaction> findByPostIdIn(Collection<UUID> postIds);
}
