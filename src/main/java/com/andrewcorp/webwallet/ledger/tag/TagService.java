package com.andrewcorp.webwallet.ledger.tag;

import com.andrewcorp.webwallet.common.error.ApiException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {

    private final TagRepository tags;

    public TagService(TagRepository tags) {
        this.tags = tags;
    }

    @Transactional(readOnly = true)
    public List<Tag> list(Long userId) {
        return tags.findByUserIdOrderByNameAsc(userId);
    }

    /** Loads tags of the user by id; an id of a missing or foreign tag is a validation error of {@code field}. */
    @Transactional(readOnly = true)
    public Set<Tag> findAll(Long userId, Collection<Long> ids, String field) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<Tag> found = new HashSet<>(tags.findByUserIdAndIdIn(userId, ids));
        if (found.size() != new HashSet<>(ids).size()) {
            throw ApiException.invalid(field, "error.notFound");
        }
        return found;
    }

    @Transactional
    public Tag create(Long userId, String name) {
        String trimmed = name.trim();
        if (tags.existsByUserIdAndNameIgnoreCase(userId, trimmed)) {
            throw ApiException.invalid("name", "error.duplicateName");
        }
        return tags.save(new Tag(userId, trimmed));
    }

    @Transactional
    public Tag rename(Long userId, Long id, String name) {
        Tag tag = tags.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
        String trimmed = name.trim();
        if (!tag.getName().equalsIgnoreCase(trimmed) && tags.existsByUserIdAndNameIgnoreCase(userId, trimmed)) {
            throw ApiException.invalid("name", "error.duplicateName");
        }
        tag.setName(trimmed);
        return tag;
    }

    /** The tag is removed from all transactions. */
    @Transactional
    public void delete(Long userId, Long id) {
        tags.delete(tags.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound));
    }
}
