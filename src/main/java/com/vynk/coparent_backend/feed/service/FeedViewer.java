package com.vynk.coparent_backend.feed.service;

import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyRelative;
import com.vynk.coparent_backend.family.entity.FamilyRole;
import com.vynk.coparent_backend.feed.entity.Post;
import com.vynk.coparent_backend.feed.entity.PostCircle;
import com.vynk.coparent_backend.user.entity.User;

/**
 * Qui regarde le fil : un parent ({@code role}) ou un proche actif ({@code relative}). Un proche ne voit que les
 * publications « Famille », et pas celles masquées dont il n'est pas l'auteur.
 */
public record FeedViewer(Family family, User user, FamilyRole role, FamilyRelative relative) {

    public boolean isParent() {
        return relative == null;
    }

    /** Clé d'une réaction : le rôle du parent ou l'id du proche. */
    public String reactor() {
        return isParent() ? role.name() : relative.getId().toString();
    }

    public boolean isAuthorOf(Post post) {
        return isParent()
                ? post.getAuthorRelative() == null && post.getAuthorRole() == role
                : post.getAuthorRelative() != null && post.getAuthorRelative().getId().equals(relative.getId());
    }

    public boolean canSee(Post post) {
        return isParent() || post.getCircle() == PostCircle.FAMILY && (!post.isHidden() || isAuthorOf(post));
    }
}
