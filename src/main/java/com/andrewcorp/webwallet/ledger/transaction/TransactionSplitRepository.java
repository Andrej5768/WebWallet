package com.andrewcorp.webwallet.ledger.transaction;

import java.util.Collection;
import org.springframework.data.repository.Repository;

public interface TransactionSplitRepository extends Repository<TransactionSplit, Long> {

    boolean existsByCategoryIdIn(Collection<Long> categoryIds);
}
