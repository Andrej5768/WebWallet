package com.andrewcorp.webwallet.ledger.tag;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
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
    public List<TagResponse> list(@CurrentUserId Long userId) {
        return tagService.list(userId).stream().map(TagResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse create(@CurrentUserId Long userId, @Valid @RequestBody TagRequest request) {
        return TagResponse.from(tagService.create(userId, request));
    }

    @Operation(
            summary = "Rename a tag or change its color",
            description = "An omitted color keeps its value; a color sent as null is removed")
    @PutMapping("/{id}")
    public TagResponse rename(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody TagRequest request) {
        return TagResponse.from(tagService.update(userId, id, request));
    }

    @Operation(summary = "Delete a tag; it is removed from all transactions")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        tagService.delete(userId, id);
    }
}
