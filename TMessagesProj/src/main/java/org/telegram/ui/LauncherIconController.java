package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.fenixuz.utils.LanguageCode;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx),
                    i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        // Novagram brand icon + color variants. Each maps to an <activity-alias> in AndroidManifest.
        // As in Telegram, the picker clips the background (the variant's gradient) to a circle and draws the
        // foreground over it: the white logo, here 15% smaller than on the home screen (ic_launcher_logo) so
        // it sits inside the circle.
        // The name comes from Telegram's own string for Default, and from LanguageCode for our colours, which
        // Telegram's cloud packs don't have.
        DEFAULT("DefaultIcon", R.drawable.ic_launcher_bg_default, R.string.AppIconDefault, 0),
        AMETHYST("AmethystIcon", R.drawable.ic_launcher_bg_amethyst, 0, 497),
        EMERALD("EmeraldIcon", R.drawable.ic_launcher_bg_emerald, 0, 498),
        CRIMSON("CrimsonIcon", R.drawable.ic_launcher_bg_crimson, 0, 499),
        MINT("MintIcon", R.drawable.ic_launcher_bg_mint, 0, 500),
        VIOLET("VioletIcon", R.drawable.ic_launcher_bg_violet, 0, 501),
        BLACK("BlackIcon", R.drawable.ic_launcher_bg_black, 0, 502),
        GREEN("GreenIcon", R.drawable.ic_launcher_bg_green, 0, 503),
        SUNSET("SunsetIcon", R.drawable.ic_launcher_bg_sunset, 0, 504),
        COFFEE("CoffeeIcon", R.drawable.ic_launcher_bg_coffee, 0, 505);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final int titleCode;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        /** The colour's name in the app's language. */
        public String getTitle() {
            return titleCode != 0 ? LanguageCode.INSTANCE.getMyTitles(titleCode) : LocaleController.getString(title);
        }

        LauncherIcon(String key, int background, int title, int titleCode) {
            this.key = key;
            this.background = background;
            this.foreground = R.drawable.app_icon_picker_logo;
            this.title = title;
            this.titleCode = titleCode;
            this.premium = false;
        }
    }
}
