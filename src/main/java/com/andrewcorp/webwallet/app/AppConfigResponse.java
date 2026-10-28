package com.andrewcorp.webwallet.app;

/**
 * What the native apps need to know about this server before signing in.
 *
 * @param android the versions of the Android app this server supports
 * @param features which optional features are on, so that the app hides the others
 */
public record AppConfigResponse(AndroidVersions android, AppFeatures features) {

    /**
     * @param minSupportedVersionCode the app refuses to run below it and sends the user to the store
     * @param latestVersionCode the app offers an update below it
     * @param storeUrl where to send the user for the update
     */
    public record AndroidVersions(int minSupportedVersionCode, int latestVersionCode, String storeUrl) {}

    /**
     * @param googleSignIn whether sign-in with Google is configured on this server
     * @param marketSearch whether the market data provider is configured, so that instruments can be searched
     * @param telegram whether the Telegram bot is configured
     * @param push whether push notifications can be delivered to the apps
     * @param demo whether a demo sandbox can be opened without registration
     */
    public record AppFeatures(
            boolean googleSignIn, boolean marketSearch, boolean telegram, boolean push, boolean demo) {}
}
