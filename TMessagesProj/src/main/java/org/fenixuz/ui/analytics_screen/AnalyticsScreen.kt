package org.fenixuz.ui.analytics_screen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fenixuz.analytics.AnalyticsRemote
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieImageView
import java.util.Locale

/**
 * Novagram "Analytics" — two anonymous growth numbers (installs, total accounts) presented as a single
 * Telegram-native card sitting at the top of the grey settings page: a tap-to-replay mascot, a caption, then
 * the two big count-up figures stacked inside the card, separated by hair-line dividers. Each figure carries
 * a small "Bu nima" badge to its right that pops up the explanation.
 *
 * One thing we keep BETTER than pro (pro froze behind a modal spinner): the last-known numbers are cached in
 * [AnalyticsRemote] and animated the MOMENT the screen opens — installs start from the 500K base on a
 * first-ever open, so nothing is blank — then the live values glide in from the background.
 * Big numbers are grouped ("500,001") for readability.
 */
class AnalyticsScreen : BaseFragment() {

    private var installs: Counter? = null
    private var accounts: Counter? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(LanguageCode.getMyTitles(333))
        actionBar.setCastShadows(false) // seamless toolbar that blends into the page
        // Match the toolbar to the grey settings backdrop so it dissolves into the page — no visible header bar.
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false)
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarWhiteSelector), false)
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        // Telegram's standard grey settings backdrop — the card reads as a clean white panel on top of it.
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

        // The one rounded white card that holds everything, anchored at the top of the page.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            }
        }

        // Playful Telegram mascot at the top — tap to replay, exactly like pro.
        val mascot = RLottieImageView(context).apply {
            setAnimation(R.raw.utyan_gigagroup, dp(130), dp(130))
            playAnimation()
            setOnClickListener {
                val d = animatedDrawable
                if (d != null && !d.isRunning) {
                    d.setCurrentFrame(0, false)
                    playAnimation()
                }
            }
        }
        card.addView(mascot, LinearLayout.LayoutParams(dp(140), dp(140)))

        card.addView(TextView(context).apply {
            text = LanguageCode.getMyTitles(336)                  // App usage numbers
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(2), dp(16), 0)
        })

        addDivider(context, card)
        installs = addMetric(
            context, card,
            LanguageCode.getMyTitles(334),                        // Number of Novagram users
            LanguageCode.getMyTitles(338),                        // …explanation
            Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
        )
        addDivider(context, card)
        accounts = addMetric(
            context, card,
            LanguageCode.getMyTitles(335),                        // Active accounts
            LanguageCode.getMyTitles(339),                        // …explanation
            0xFF069DFD.toInt()                                    // Novagram brand blue (was green)
        )

        column.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scroll.addView(column, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))
        fragmentView = scroll

        loadData(context)
        return scroll
    }

    /** A faint hair-line that separates the sections inside the card. */
    private fun addDivider(context: Context, parent: LinearLayout) {
        val line = View(context).apply { setBackgroundColor(Theme.getColor(Theme.key_divider)) }
        parent.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))).apply {
            topMargin = dp(18)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
    }

    /**
     * One metric block: the big accent count centred in the card with the small "Bu nima" info badge pinned
     * to its right, then a quiet grey label underneath. Tapping the badge slides up the explanation sheet.
     * Returns the [Counter] that animates the number.
     */
    private fun addMetric(context: Context, parent: LinearLayout, label: String, explanation: String, accent: Int): Counter {
        val number = TextView(context).apply {
            text = "0"
            setTextColor(accent)
            textSize = 42f
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val badge = TextView(context).apply {
            text = "?"
            textSize = 13f
            typeface = AndroidUtilities.bold()
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0A93E8.toInt())             // Novagram brand blue (matches the logo)
            }
            setOnClickListener { showInfoSheet(context, explanation) }
        }

        // The compact "?" badge hugs the number's lower-right while the number stays DEAD-CENTRE: an invisible
        // dp(24) spacer the same size as the badge sits on the left, so the number is perfectly centred between
        // them. The badge is tiny (~24dp) so the group fits even on the narrowest / split-screen widths.
        val spacer = View(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(spacer, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.BOTTOM
            rightMargin = dp(6)
            bottomMargin = dp(2)
        })
        row.addView(number, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        row.addView(badge, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(6)
            bottomMargin = dp(2)
        })
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        parent.addView(TextView(context).apply {
            text = label
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(2), dp(16), 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        return Counter(number)
    }

    /** Slide-up explanation sheet: the description in clean, readable body text (no italics). */
    private fun showInfoSheet(context: Context, message: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(28))
            setBackgroundColor(Theme.getColor(Theme.key_dialogBackground))
        }
        container.addView(TextView(context).apply {
            text = message
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            textSize = 16f
            setLineSpacing(dp(3).toFloat(), 1f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        BottomSheet.Builder(context, false).setCustomView(container).show()
    }

    /** Show instantly from cache (or the 500K/250K base on first open), then glide to the live numbers. */
    private fun loadData(context: Context) {
        installs?.to(AnalyticsRemote.cachedInstallsOrBase(context))
        accounts?.to(AnalyticsRemote.cachedAccountsOrBase(context))

        AnalyticsRemote.getInstallsDisplay(context) { fresh -> installs?.to(fresh) }
        AnalyticsRemote.getAccountsDisplay(context) { fresh -> accounts?.to(fresh) }
    }

    override fun hasForceLightStatusBar(): Boolean = false

    /** Animates a TextView from its current value to a target; re-targets smoothly when fresh data lands. */
    private class Counter(private val view: TextView) {
        private var animator: ValueAnimator? = null
        private var shown: Long = 0L
        private var target: Long = -1L

        fun to(value: Long) {
            val end = value.coerceIn(0L, Int.MAX_VALUE.toLong())
            if (end == target) return                 // already animating/animated to this exact number
            target = end
            animator?.cancel()
            animator = ValueAnimator.ofInt(shown.toInt(), end.toInt()).apply {
                duration = if (shown == 0L) 900L else 500L   // first reveal lingers; later corrections are quick
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    shown = (it.animatedValue as Int).toLong()
                    view.text = formatNumber(shown)
                }
                start()
            }
        }
    }
}

private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())

private fun formatNumber(value: Long): String = String.format(Locale.US, "%,d", value)
