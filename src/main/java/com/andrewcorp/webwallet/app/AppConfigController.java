package com.andrewcorp.webwallet.app;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "App")
@RestController
@RequestMapping("/api/v1/app")
public class AppConfigController {

    private final AppConfigService appConfig;

    public AppConfigController(AppConfigService appConfig) {
        this.appConfig = appConfig;
    }

    @Operation(
            summary = "Versions and features for the native apps",
            description = "Public. The app refuses to run below minSupportedVersionCode and hides features that"
                    + " are off on this server.")
    @GetMapping("/config")
    public ResponseEntity<AppConfigResponse> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(appConfig.config());
    }
}
