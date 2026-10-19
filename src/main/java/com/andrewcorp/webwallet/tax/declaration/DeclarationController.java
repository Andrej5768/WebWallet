package com.andrewcorp.webwallet.tax.declaration;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.tax.declaration.DeclarationService.DeclarationFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Taxes")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/taxes")
public class DeclarationController {

    private final TaxProfileService profiles;
    private final DeclarationService declarations;

    public DeclarationController(TaxProfileService profiles, DeclarationService declarations) {
        this.profiles = profiles;
        this.declarations = declarations;
    }

    @Operation(summary = "The taxpayer details for the declarations; 404 until they are entered")
    @GetMapping("/profile")
    public TaxProfileData profile(@CurrentUserId Long userId) {
        return profiles.find(userId).orElseThrow(ApiException::notFound);
    }

    @Operation(
            summary = "Replace the taxpayer details for the declarations",
            description = "Kept encrypted. The tax office codes (region and district, C_REG and C_RAJ) are those of"
                    + " the office the taxpayer is registered with.")
    @PutMapping("/profile")
    public TaxProfileData saveProfile(@CurrentUserId Long userId, @Valid @RequestBody TaxProfileData request) {
        return profiles.save(userId, request);
    }

    @Operation(
            summary = "The declaration of a sole proprietor of group 3 for a quarter, as XML of the tax service",
            description = "F0103309 with the amounts from the start of the year; for the fourth quarter with F0133109,"
                    + " the social contribution of the year, both in a ZIP. To be opened, checked and signed in the"
                    + " tax service cabinet.")
    @GetMapping("/declarations/fop/{year}/{quarter}")
    public ResponseEntity<byte[]> fop(
            @CurrentUserId Long userId,
            @PathVariable @Min(2025) @Max(2100) int year,
            @PathVariable @Min(1) @Max(4) int quarter) {
        return download(declarations.fop(userId, year, quarter), "fop-" + year + "-q" + quarter);
    }

    @Operation(
            summary = "The yearly declaration of property and income, as XML of the tax service",
            description = "F0100215 with F0121215 (Ф1) when there were sales, in a ZIP: foreign dividends with the"
                    + " tax paid abroad and the investment profit. To be opened, checked and signed in the tax service"
                    + " cabinet.")
    @GetMapping("/declarations/property/{year}")
    public ResponseEntity<byte[]> property(@CurrentUserId Long userId, @PathVariable @Min(2025) @Max(2100) int year) {
        return download(declarations.property(userId, year), "declaration-" + year);
    }

    /** One file as it is, several in a ZIP. */
    private static ResponseEntity<byte[]> download(List<DeclarationFile> files, String zipName) {
        boolean single = files.size() == 1;
        String name = single ? files.get(0).name() : zipName + ".zip";
        return ResponseEntity.ok()
                .contentType(single ? MediaType.APPLICATION_XML : MediaType.parseMediaType("application/zip"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(single ? files.get(0).content() : DeclarationService.zip(files));
    }
}
