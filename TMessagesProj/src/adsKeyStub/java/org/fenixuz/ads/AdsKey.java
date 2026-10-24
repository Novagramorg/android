package org.fenixuz.ads;

import org.telegram.messenger.BuildConfig;

/**
 * Novagram: the ads API key (X-API-Key), straight from BuildConfig (local.properties). Used by builds without the
 * private build file; a build that has it supplies its own AdsKey, and BuildConfig then carries no key.
 */
public final class AdsKey {

    private AdsKey() {
    }

    public static String apiKey() {
        return BuildConfig.ADS_API_KEY;
    }
}
