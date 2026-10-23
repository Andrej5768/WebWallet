package com.andrewcorp.webwallet.app;

import com.andrewcorp.webwallet.auth.google.GoogleSignInService;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.investment.market.ListingSearch;
import org.springframework.stereotype.Service;

/**
 * Collects the configuration the native apps read on start: the supported versions from the properties and the
 * features from the same sources the web endpoints use, so that both clients always get the same answer.
 */
@Service
public class AppConfigService {

    private final AppProperties properties;
    private final GoogleSignInService googleSignIn;
    private final ListingSearch listingSearch;

    public AppConfigService(AppProperties properties, GoogleSignInService googleSignIn, ListingSearch listingSearch) {
        this.properties = properties;
        this.googleSignIn = googleSignIn;
        this.listingSearch = listingSearch;
    }

    public AppConfigResponse config() {
        AppProperties.Android android = properties.android();
        return new AppConfigResponse(
                new AppConfigResponse.AndroidVersions(
                        android.minSupportedVersionCode(), android.latestVersionCode(), android.storeUrl()),
                new AppConfigResponse.AppFeatures(
                        googleSignIn.enabled(),
                        listingSearch.enabled(),
                        properties.telegram().enabled(),
                        // B4: becomes "FCM configured" once push delivery exists
                        false));
    }
}
