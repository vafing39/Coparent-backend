package com.vynk.coparent_backend.family.repository;

import com.vynk.coparent_backend.family.entity.Family;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FamilyRepository extends JpaRepository<Family, UUID> {
}
