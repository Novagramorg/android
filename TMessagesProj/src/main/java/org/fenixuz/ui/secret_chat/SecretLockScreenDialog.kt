package org.fenixuz.ui.secret_chat

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.R
import org.fenixuz.ui.lock.LockCredential
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity

/**
 * Transparent full-screen dialog that hosts the [SecretLockScreen] over whatever fragment is open
 * (the secret folder, a locked chat, …). While shown, the app container is scaled up to mirror
 * Telegram's app-lock reveal animation. [onUnlocked] (optional) fires once the passcode is accepted, at the moment
 * [unlockMode] says.
 * Back closes [baseFragment] too when [finishOnBack] (a locked screen must not stay open behind it); otherwise it only
 * closes the lock screen (a code prompt over an ordinary screen).
 */
class SecretLockScreenDialog @JvmOverloads constructor(
    private val baseFragment: BaseFragment,
    context: Context,
    credential: LockCredential,
    private val onUnlocked: Runnable? = null,
    private val finishOnBack: Boolean = true,
    private val unlockMode: Int = UNLOCK_AFTER_HIDE
) : Dialog(context, R.style.TransparentDialog) {

    companion object {
        /** [onUnlocked] runs once the lock screen has faded out: the lock sits over the very screen it guards. */
        const val UNLOCK_AFTER_HIDE = 0

        /**
         * [onUnlocked] runs as soon as the code is accepted and opens a screen, without animation, under the fading lock
         * screen, which then reveals that screen instead of the one below for a moment.
         */
        const val UNLOCK_BEHIND = 1

        /**
         * [onUnlocked] runs as soon as the code is accepted and shows a dialog. That dialog would open above the fading
         * lock screen, so the lock screen closes at once instead.
         */
        const val UNLOCK_THEN_CLOSE = 2
    }

    private val windowView: FrameLayout = FrameLayout(context)
    val secretLockScreen: SecretLockScreen
    private var unlockDone = false
    private var closed = false

    init {
        if (Build.VERSION.SDK_INT >= 21) {
            windowView.fitsSystemWindows = true
            windowView.setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsets.CONSUMED
                } else {
                    @Suppress("DEPRECATION")
                    insets.consumeSystemWindowInsets()
                }
            }
        }

        secretLockScreen = SecretLockScreen(context, credential, object : SecretLockScreen.SecretLockScreenInterFace {
            override fun onAccepted() {
                super.onAccepted()
                if (unlockMode == UNLOCK_AFTER_HIDE) return
                unlockDone = true
                onUnlocked?.run()
                if (unlockMode == UNLOCK_THEN_CLOSE) {
                    close()
                }
            }

            override fun onHidden() {
                super.onHidden()
                close()
                if (!unlockDone) {
                    unlockDone = true
                    onUnlocked?.run()
                }
            }

            override fun onAnimationUpdate(open: Float) {
                super.onAnimationUpdate(open)
                // Once closed early (UNLOCK_THEN_CLOSE) the fade still runs: it must not scale the app again.
                if (closed) return
                val drawerLayoutContainer = LaunchActivity.instance?.drawerLayoutContainer ?: return
                drawerLayoutContainer.scaleX = AndroidUtilities.lerp(1f, 1.25f, open)
                drawerLayoutContainer.scaleY = AndroidUtilities.lerp(1f, 1.25f, open)
            }
        })

        windowView.addView(
            secretLockScreen,
            LayoutHelper.createFrame(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.FILL
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val window = window ?: return
        window.setWindowAnimations(R.style.DialogNoAnimation)
        setContentView(
            windowView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val params = window.attributes
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.FILL
        params.dimAmount = 0f
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (!BuildVars.DEBUG_PRIVATE_VERSION) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
        }
        if (Build.VERSION.SDK_INT >= 21) {
            params.flags = params.flags or (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = params

        windowView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_VISIBLE)
    }

    override fun onBackPressed() {
        if (secretLockScreen.onBackPressed()) {
            if (finishOnBack) {
                baseFragment.finishFragment()
            } else {
                close()
            }
        }
    }

    /** Closes the lock screen and puts the app container back to its normal scale. */
    private fun close() {
        closed = true
        dismiss()
        val drawerLayoutContainer = LaunchActivity.instance?.drawerLayoutContainer
        if (drawerLayoutContainer != null) {
            drawerLayoutContainer.scaleX = 1f
            drawerLayoutContainer.scaleY = 1f
        }
    }
}
