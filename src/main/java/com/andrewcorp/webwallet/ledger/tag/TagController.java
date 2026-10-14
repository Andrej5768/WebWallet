package com.andrewcorp.webwallet.ledger.tag;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.NameRequest;
import com.andrewcorp.webwallet.ledger.NamedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Tags")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public List<NamedResponse> list(@CurrentUserId Long userId) {
        return tagService.list(userId).stream().map(TagController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NamedResponse create(@CurrentUserId Long userId, @Valid @RequestBody NameRequest request) {
        return toResponse(tagService.create(userId, request.name()));
    }

    @PutMapping("/{id}")
    public NamedResponse rename(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody NameRequest request) {
        return toResponse(tagService.rename(userId, id, request.name()));
    }

    @Operation(summary = "Delete a tag; it is removed from all transactions")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        tagService.delete(userId, id);
    }

    private static NamedResponse toResponse(Tag tag) {
        return new NamedResponse(tag.getId(), tag.getName());
    }
}
