package com.andrewcorp.webwallet.ledger.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderBySortOrderAscNameAsc(Long userId);

    Optional<Category> findByIdAndUserId(Long id, Long userId);

    boolean existsByParentId(Long parentId);

    List<Category> findByParentId(Long parentId);
}
