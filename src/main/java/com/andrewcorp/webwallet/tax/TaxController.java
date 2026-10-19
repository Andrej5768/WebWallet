package com.andrewcorp.webwallet.tax;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.tax.TaxDtos.DividendDocumentRequest;
import com.andrewcorp.webwallet.tax.TaxDtos.FopReport;
import com.andrewcorp.webwallet.tax.TaxDtos.IncomeCategoriesRequest;
import com.andrewcorp.webwallet.tax.TaxDtos.InvestmentTaxReport;
import com.andrewcorp.webwallet.tax.TaxDtos.QuarterPaymentRequest;
import com.andrewcorp.webwallet.tax.TaxDtos.TaxSettingsRequest;
import com.andrewcorp.webwallet.tax.TaxDtos.TaxSettingsResponse;
import com.andrewcorp.webwallet.tax.TaxDtos.TransactionMarkRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Taxes")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/taxes")
public class TaxController {

    private final TaxSettingsService settings;
    private final FopTaxService fop;
    private final InvestmentTaxService investments;

    public TaxController(TaxSettingsService settings, FopTaxService fop, InvestmentTaxService investments) {
        this.settings = settings;
        this.fop = fop;
        this.investments = investments;
    }

    @Operation(
            summary = "The tax rates of a year: the user's own values over the defaults, and where to check them",
            description = "The defaults are those known when the year was added and may be outdated: the user"
                    + " replaces any of them.")
    @GetMapping("/settings/{year}")
    public TaxSettingsResponse settings(@CurrentUserId Long userId, @PathVariable @Min(2000) @Max(2100) int year) {
        return settings.get(userId, year);
    }

    @Operation(summary = "Replace the user's own tax values of a year; null takes the default")
    @PutMapping("/settings/{year}")
    public TaxSettingsResponse updateSettings(
            @CurrentUserId Long userId,
            @PathVariable @Min(2000) @Max(2100) int year,
            @Valid @RequestBody TaxSettingsRequest request) {
        return settings.update(userId, year, request);
    }

    @Operation(
            summary = "Replace the income categories whose income is income of the sole proprietor on any account",
            description = "Their subcategories count too; the income of FOP accounts counts whatever its category.")
    @PutMapping("/income-categories")
    public List<Long> incomeCategories(
            @CurrentUserId Long userId, @Valid @RequestBody IncomeCategoriesRequest request) {
        return settings.updateIncomeCategories(userId, request.categoryIds());
    }

    @Operation(
            summary = "Add a transaction to the income of the sole proprietor, take it out, or forget the mark (null)")
    @PutMapping("/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mark(
            @CurrentUserId Long userId, @PathVariable Long transactionId, @RequestBody TransactionMarkRequest request) {
        settings.mark(userId, transactionId, request.included());
    }

    @Operation(summary = "The income, taxes, social contribution and deadlines of the sole proprietor by quarter")
    @GetMapping("/fop/{year}")
    public FopReport fop(@CurrentUserId Long userId, @PathVariable @Min(2000) @Max(2100) int year) {
        return fop.report(userId, year);
    }

    @Operation(summary = "Mark the taxes of a quarter as paid or not")
    @PutMapping("/fop/{year}/quarters/{quarter}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markPaid(
            @CurrentUserId Long userId,
            @PathVariable @Min(2000) @Max(2100) int year,
            @PathVariable @Min(1) @Max(4) int quarter,
            @RequestBody QuarterPaymentRequest request) {
        settings.markPaid(userId, year, quarter, request.paid());
    }

    @Operation(
            summary = "The taxes of the investment income of a year: dividends, sales, the carried loss",
            description = "In hryvnias at the NBU rates of the dates: the profit of a sale counts its lots (FIFO) at"
                    + " the rates of their purchases, so a change of the exchange rate is profit too.")
    @GetMapping("/investments/{year}")
    public InvestmentTaxReport investments(@CurrentUserId Long userId, @PathVariable @Min(2000) @Max(2100) int year) {
        return investments.report(userId, year);
    }

    @Operation(summary = "Mark whether the document of the tax withheld abroad from a dividend exists; null forgets")
    @PutMapping("/investments/dividends/{operationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dividendDocument(
            @CurrentUserId Long userId, @PathVariable Long operationId, @RequestBody DividendDocumentRequest request) {
        investments.setDocument(userId, operationId, request.document());
    }
}
