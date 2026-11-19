package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    // Novagram: the freeze watchdog's reports go to this module's Crashlytics. Any failure here (Firebase not
    // initialised, ...) stays inside: a diagnostic must never take the app down.
    @Override
    protected org.fenixuz.diag.DiagReporter onCreateDiagReporter() {
        return new org.fenixuz.diag.DiagReporter() {
            @Override
            public void recordNonFatal(Throwable error) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(error);
                } catch (Throwable ignore) {
                }
            }

            @Override
            public void setKey(String key, String value) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCustomKey(key, value);
                } catch (Throwable ignore) {
                }
            }
        };
    }
}
