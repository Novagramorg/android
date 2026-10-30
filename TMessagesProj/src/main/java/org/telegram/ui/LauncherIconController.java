package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
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
        DEFAULT("DefaultIcon", R.drawable.ic_launcher_bg_default, R.drawable.app_icon_picker_logo, R.string.AppIconDefault),
        AMETHYST("AmethystIcon", R.drawable.ic_launcher_bg_amethyst, R.drawable.app_icon_picker_logo, R.string.AppIconAmethyst),
        EMERALD("EmeraldIcon", R.drawable.ic_launcher_bg_emerald, R.drawable.app_icon_picker_logo, R.string.AppIconEmerald),
        CRIMSON("CrimsonIcon", R.drawable.ic_launcher_bg_crimson, R.drawable.app_icon_picker_logo, R.string.AppIconCrimson),
        MINT("MintIcon", R.drawable.ic_launcher_bg_mint, R.drawable.app_icon_picker_logo, R.string.AppIconMint),
        VIOLET("VioletIcon", R.drawable.ic_launcher_bg_violet, R.drawable.app_icon_picker_logo, R.string.AppIconViolet);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
