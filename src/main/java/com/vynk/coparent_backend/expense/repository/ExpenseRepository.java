package com.vynk.coparent_backend.expense.repository;

import com.vynk.coparent_backend.expense.entity.Expense;
import com.vynk.coparent_backend.expense.entity.ExpenseStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    @EntityGraph(attributePaths = {"createdBy", "children"})
    Optional<Expense> findByIdAndFamilyId(UUID id, UUID familyId);

    /** Les dépenses de la famille dans ces statuts, la plus récente d'abord. */
    @EntityGraph(attributePaths = {"createdBy", "children"})
    List<Expense> findByFamilyIdAndStatusInOrderByPaidOnDescCreatedAtDesc(UUID familyId,
                                                                         Collection<ExpenseStatus> statuses);
}
