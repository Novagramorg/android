package org.fenixuz.ui.secret_chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewSwitcher
import org.fenixuz.ui.lock.LockEditor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CodeFieldContainer
import org.telegram.ui.CodeNumberField
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.CombinedDrawable
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.CustomPhoneKeyboardView
import org.telegram.ui.Components.Easings
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.OutlineTextContainerView
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.telegram.ui.Components.TextViewSwitcher
import org.telegram.ui.Components.TransformableLoginButtonView
import org.telegram.ui.Components.VerticalPositionAutoAnimator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Passcode creation / change screen for the secret folder. Mirrors Telegram's own passcode setup UI
 * (PIN or alphanumeric password, custom keyboard, confirm step) but stores into [SecretPassword]
 * rather than the app-wide passcode.
 */
class SecretPasscodeScreen(
    private val editor: LockEditor,
    private val passwordType: SecretPasswordType
) : BaseFragment() {

    private var keyboardView: CustomPhoneKeyboardView? = null
    private var onShowKeyboardCallback: Runnable? = null
    private var otherItem: ActionBarMenuItem? = null
    private val ID_SWITCH_TYPE = 1
    private var currentPasswordType = 0
    private var codeFieldContainer: CodeFieldContainer? = null
    private var passwordEditText: EditTextBoldCursor? = null
    private var passwordButton: ImageView? = null
    private var passcodeSetStep = 0
    private var descriptionTextSwitcher: TextViewSwitcher? = null
    private var outlinePasswordView: OutlineTextContainerView? = null
    private var floatingButtonAnimator: Animator? = null
    private var floatingAutoAnimator: VerticalPositionAutoAnimator? = null
    private var floatingButtonContainer: FrameLayout? = null
    private var lockImageView: RLottieImageView? = null
    private var titleTextView: TextView? = null
    private var passcodesDoNotMatchTextView: TextView? = null
    private var firstPassword: String? = null
    private var floatingButtonIcon: TransformableLoginButtonView? = null

    private var postedHidePasscodesDoNotMatch = false

    private val hidePasscodesDoNotMatch = Runnable {
        postedHidePasscodesDoNotMatch = false
        AndroidUtilities.updateViewVisibilityAnimated(passcodesDoNotMatchTextView, false)
    }

    @SuppressLint("ObjectAnimatorBinding")
    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(false)

        val frameLayout = FrameLayout(context)

        val scrollView = ScrollView(context)
        scrollView.addView(
            frameLayout,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat())
        )
        scrollView.isFillViewport = true
        val fragmentContentView: View = scrollView

        val contentView: SizeNotifierFrameLayout = object : SizeNotifierFrameLayout(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val frameBottom: Int
                if (keyboardView?.visibility != GONE && measureKeyboardHeight() >= AndroidUtilities.dp(20f)) {
                    if (isCustomKeyboardVisible()) {
                        fragmentContentView.layout(
                            0, 0, measuredWidth,
                            (measuredHeight - AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat()) + measureKeyboardHeight()).also {
                                frameBottom = it
                            })
                    } else {
                        fragmentContentView.layout(0, 0, measuredWidth, measuredHeight.also { frameBottom = it })
                    }
                } else if (keyboardView?.visibility != GONE) {
                    fragmentContentView.layout(
                        0, 0, measuredWidth,
                        (measuredHeight - AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat())).also {
                            frameBottom = it
                        })
                } else {
                    fragmentContentView.layout(0, 0, measuredWidth, measuredHeight.also { frameBottom = it })
                }

                keyboardView?.layout(
                    0, frameBottom, measuredWidth,
                    frameBottom + AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat())
                )
                notifyHeightChanged()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(width, height)

                var frameHeight = height
                if (keyboardView?.visibility != GONE && measureKeyboardHeight() < AndroidUtilities.dp(20f)) {
                    frameHeight -= AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat())
                }
                fragmentContentView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(frameHeight, MeasureSpec.EXACTLY)
                )
                keyboardView?.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat()),
                        MeasureSpec.EXACTLY
                    )
                )
            }
        }

        contentView.setDelegate { keyboardHeight: Int, _: Boolean ->
            if (keyboardHeight >= AndroidUtilities.dp(20f) && onShowKeyboardCallback != null) {
                onShowKeyboardCallback?.run()
                onShowKeyboardCallback = null
            }
        }

        fragmentView = contentView
        contentView.addView(
            fragmentContentView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
        )

        keyboardView = CustomPhoneKeyboardView(context)
        keyboardView?.visibility = if (isCustomKeyboardVisible()) View.VISIBLE else View.GONE
        contentView.addView(
            keyboardView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP)
        )

        actionBar.backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite)
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false)
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarWhiteSelector), false)
        actionBar.castShadows = false
        val menu = actionBar.createMenu()

        val switchItem: ActionBarMenuSubItem?
        if (passwordType == SecretPasswordType.SET_NEW) {
            otherItem = menu.addItem(0, R.drawable.ic_ab_other)
            switchItem = otherItem?.addSubItem(
                ID_SWITCH_TYPE,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.PasscodeSwitchToPassword)
            )
        } else {
            switchItem = null
        }

        actionBar.setActionBarMenuOnItemClick(object : ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                } else if (id == ID_SWITCH_TYPE) {
                    currentPasswordType =
                        if (currentPasswordType == SharedConfig.PASSCODE_TYPE_PIN) SharedConfig.PASSCODE_TYPE_PASSWORD else SharedConfig.PASSCODE_TYPE_PIN
                    AndroidUtilities.runOnUIThread({
                        switchItem!!.setText(LocaleController.getString(if (currentPasswordType == SharedConfig.PASSCODE_TYPE_PIN) R.string.PasscodeSwitchToPassword else R.string.PasscodeSwitchToPIN))
                        switchItem.setIcon(if (currentPasswordType == SharedConfig.PASSCODE_TYPE_PIN) R.drawable.msg_permissions else R.drawable.msg_pin_code)
                        showKeyboard()
                        if (isPinCode()) {
                            passwordEditText?.inputType =
                                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            AndroidUtilities.updateViewVisibilityAnimated(passwordButton, true, 0.1f, false)
                        }
                    }, 150)
                    passwordEditText?.setText("")
                    codeFieldContainer?.let {
                        for (f: CodeNumberField in it.codeField) {
                            f.setText("")
                        }
                    }
                    updateFields()
                }
            }
        })

        val codeContainer = FrameLayout(context)

        val innerLinearLayout = LinearLayout(context)
        innerLinearLayout.orientation = LinearLayout.VERTICAL
        innerLinearLayout.gravity = Gravity.CENTER_HORIZONTAL
        frameLayout.addView(
            innerLinearLayout,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        )

        lockImageView = RLottieImageView(context)
        lockImageView?.isFocusable = false
        lockImageView?.setAnimation(R.raw.tsv_setup_intro, 120, 120)
        lockImageView?.setAutoRepeat(false)
        lockImageView?.playAnimation()
        lockImageView?.visibility =
            if (!AndroidUtilities.isSmallScreen() && AndroidUtilities.displaySize.x < AndroidUtilities.displaySize.y) View.VISIBLE else View.GONE
        innerLinearLayout.addView(
            lockImageView,
            LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL)
        )

        titleTextView = TextView(context)
        titleTextView?.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        titleTextView?.typeface = AndroidUtilities.bold()
        titleTextView?.text = if (passwordType == SecretPasswordType.SET_NEW) {
            if (editor.hasPassword()) LocaleController.getString(R.string.EnterNewPasscode)
            else LocaleController.getString(R.string.CreatePasscode)
        } else {
            LocaleController.getString(R.string.EnterYourPasscode)
        }
        titleTextView?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        titleTextView?.gravity = Gravity.CENTER_HORIZONTAL
        innerLinearLayout.addView(
            titleTextView,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0
            )
        )

        descriptionTextSwitcher = TextViewSwitcher(context)
        descriptionTextSwitcher?.setFactory {
            val tv = TextView(context)
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6))
            tv.gravity = Gravity.CENTER_HORIZONTAL
            tv.setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1f)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            tv
        }
        descriptionTextSwitcher?.setInAnimation(context, R.anim.alpha_in)
        descriptionTextSwitcher?.setOutAnimation(context, R.anim.alpha_out)
        innerLinearLayout.addView(
            descriptionTextSwitcher,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20, 8, 20, 0
            )
        )

        val forgotPasswordButton = TextView(context)
        forgotPasswordButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        forgotPasswordButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton))
        forgotPasswordButton.setPadding(AndroidUtilities.dp(32f), 0, AndroidUtilities.dp(32f), 0)
        forgotPasswordButton.gravity =
            (if (isPassword()) Gravity.LEFT else Gravity.CENTER_HORIZONTAL) or Gravity.CENTER_VERTICAL
        forgotPasswordButton.setOnClickListener {
            AlertsCreator.createForgotPasscodeDialog(context).show()
        }
        // Hidden: Telegram's "forgot passcode" flow is about the app-wide passcode (log out to reset),
        // which is irrelevant to the secret folder lock and would only confuse here.
        forgotPasswordButton.visibility = View.GONE
        forgotPasswordButton.text = LocaleController.getString(R.string.ForgotPasscode)
        frameLayout.addView(
            forgotPasswordButton,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) 56 else 60).toFloat(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 16f
            )
        )
        VerticalPositionAutoAnimator.attach(forgotPasswordButton)

        passcodesDoNotMatchTextView = TextView(context)
        passcodesDoNotMatchTextView?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        passcodesDoNotMatchTextView?.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6))
        passcodesDoNotMatchTextView?.text = LocaleController.getString(R.string.PasscodesDoNotMatchTryAgain)
        passcodesDoNotMatchTextView?.setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f))
        AndroidUtilities.updateViewVisibilityAnimated(passcodesDoNotMatchTextView, false, 1f, false)
        frameLayout.addView(
            passcodesDoNotMatchTextView,
            LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 16f
            )
        )

        outlinePasswordView = OutlineTextContainerView(context)
        outlinePasswordView?.setText(LocaleController.getString(R.string.EnterPassword))

        passwordEditText = EditTextBoldCursor(context)
        passwordEditText?.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        passwordEditText?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        passwordEditText?.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        passwordEditText?.background = null
        passwordEditText?.maxLines = 1
        passwordEditText?.setLines(1)
        passwordEditText?.gravity = if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT
        passwordEditText?.isSingleLine = true
        if (passwordType == SecretPasswordType.SET_NEW) {
            passcodeSetStep = 0
            passwordEditText?.imeOptions = EditorInfo.IME_ACTION_NEXT
        } else {
            passcodeSetStep = 1
            passwordEditText?.imeOptions = EditorInfo.IME_ACTION_DONE
        }
        passwordEditText?.transformationMethod = PasswordTransformationMethod.getInstance()
        passwordEditText?.typeface = Typeface.DEFAULT
        passwordEditText?.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated))
        passwordEditText?.setCursorSize(AndroidUtilities.dp(20f))
        passwordEditText?.setCursorWidth(1.5f)

        val padding = AndroidUtilities.dp(16f)
        passwordEditText?.setPadding(padding, padding, padding, padding)
        passwordEditText?.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            outlinePasswordView!!.animateSelection((if (hasFocus) 1 else 0).toFloat())
        }

        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.HORIZONTAL
        linearLayout.gravity = Gravity.CENTER_VERTICAL
        linearLayout.addView(
            passwordEditText,
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f)
        )

        passwordButton = ImageView(context)
        passwordButton?.setImageResource(R.drawable.msg_message)
        passwordButton?.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        passwordButton?.background = Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1)
        AndroidUtilities.updateViewVisibilityAnimated(
            passwordButton,
            (passwordType == SecretPasswordType.SET_NEW) && passcodeSetStep == 0,
            0.1f, false
        )

        val isPasswordShown = AtomicBoolean(false)
        passwordEditText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if ((passwordType == SecretPasswordType.SET_NEW) && passcodeSetStep == 0) {
                    if (TextUtils.isEmpty(s) && passwordButton!!.visibility != View.GONE) {
                        if (isPasswordShown.get()) {
                            passwordButton!!.callOnClick()
                        }
                        AndroidUtilities.updateViewVisibilityAnimated(passwordButton, false, 0.1f, true)
                    } else if (!TextUtils.isEmpty(s) && passwordButton!!.visibility != View.VISIBLE) {
                        AndroidUtilities.updateViewVisibilityAnimated(passwordButton, true, 0.1f, true)
                    }
                }
            }
        })

        passwordButton!!.setOnClickListener {
            isPasswordShown.set(!isPasswordShown.get())
            val selectionStart = passwordEditText!!.selectionStart
            val selectionEnd = passwordEditText!!.selectionEnd
            passwordEditText!!.inputType =
                InputType.TYPE_CLASS_TEXT or (if (isPasswordShown.get()) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            passwordEditText!!.setSelection(selectionStart, selectionEnd)
            passwordButton!!.setColorFilter(Theme.getColor(if (isPasswordShown.get()) Theme.key_windowBackgroundWhiteInputFieldActivated else Theme.key_windowBackgroundWhiteHintText))
        }
        linearLayout.addView(
            passwordButton,
            LayoutHelper.createLinearRelatively(24f, 24f, 0, 0f, 0f, 14f, 0f)
        )

        outlinePasswordView!!.addView(
            linearLayout,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat())
        )
        codeContainer.addView(
            outlinePasswordView,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 0
            )
        )

        passwordEditText!!.setOnEditorActionListener { _, _, _ ->
            if (passcodeSetStep == 0) {
                processNext()
                return@setOnEditorActionListener true
            } else if (passcodeSetStep == 1) {
                processDone()
                return@setOnEditorActionListener true
            }
            false
        }

        passwordEditText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (postedHidePasscodesDoNotMatch) {
                    codeFieldContainer!!.removeCallbacks(hidePasscodesDoNotMatch)
                    hidePasscodesDoNotMatch.run()
                }
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {}
        })

        passwordEditText!!.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) {}
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        }

        codeFieldContainer = object : CodeFieldContainer(context) {
            override fun processNextPressed() {
                if (passcodeSetStep == 0) {
                    postDelayed({ processNext() }, 260)
                } else {
                    processDone()
                }
            }
        }
        codeFieldContainer?.setNumbersCount(4, CodeFieldContainer.TYPE_PASSCODE)
        codeFieldContainer?.let { container ->
            for (f in container.codeField) {
                f.setShowSoftInputOnFocusCompat(!isCustomKeyboardVisible())
                f.transformationMethod = PasswordTransformationMethod.getInstance()
                f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
                f.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        if (postedHidePasscodesDoNotMatch) {
                            codeFieldContainer?.removeCallbacks(hidePasscodesDoNotMatch)
                            hidePasscodesDoNotMatch.run()
                        }
                    }
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable) {}
                })
                f.onFocusChangeListener = OnFocusChangeListener { _, _ ->
                    keyboardView!!.setEditText(f)
                    keyboardView!!.setDispatchBackWhenEmpty(true)
                }
            }
        }
        codeContainer.addView(
            codeFieldContainer,
            LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_HORIZONTAL, 40f, 10f, 40f, 0f
            )
        )

        innerLinearLayout.addView(
            codeContainer,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 32, 0, 72
            )
        )

        if (passwordType == SecretPasswordType.SET_NEW) {
            frameLayout.tag = Theme.key_windowBackgroundWhite
        }

        floatingButtonContainer = FrameLayout(context)
        if (Build.VERSION.SDK_INT >= 21) {
            val animator = StateListAnimator()
            animator.addState(
                intArrayOf(android.R.attr.state_pressed),
                ObjectAnimator.ofFloat(
                    floatingButtonIcon, "translationZ",
                    AndroidUtilities.dp(2f).toFloat(), AndroidUtilities.dp(4f).toFloat()
                ).setDuration(200)
            )
            animator.addState(
                intArrayOf(),
                ObjectAnimator.ofFloat(
                    floatingButtonIcon, "translationZ",
                    AndroidUtilities.dp(4f).toFloat(), AndroidUtilities.dp(2f).toFloat()
                ).setDuration(200)
            )
            floatingButtonContainer?.stateListAnimator = animator
            floatingButtonContainer?.outlineProvider = object : ViewOutlineProvider() {
                @SuppressLint("NewApi")
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, AndroidUtilities.dp(56f), AndroidUtilities.dp(56f))
                }
            }
        }

        floatingAutoAnimator = VerticalPositionAutoAnimator.attach(floatingButtonContainer)
        frameLayout.addView(
            floatingButtonContainer,
            LayoutHelper.createFrame(
                if (Build.VERSION.SDK_INT >= 21) 56 else 60,
                (if (Build.VERSION.SDK_INT >= 21) 56 else 60).toFloat(),
                Gravity.RIGHT or Gravity.BOTTOM, 0f, 0f, 24f, 16f
            )
        )
        floatingButtonContainer!!.setOnClickListener {
            if (passwordType == SecretPasswordType.SET_NEW) {
                if (passcodeSetStep == 0) processNext() else processDone()
            } else if (passwordType == SecretPasswordType.CHANGE) {
                processDone()
            }
        }

        floatingButtonIcon = TransformableLoginButtonView(context)
        floatingButtonIcon?.setTransformType(TransformableLoginButtonView.TRANSFORM_ARROW_CHECK)
        floatingButtonIcon?.setProgress(0f)
        floatingButtonIcon?.setColor(Theme.getColor(Theme.key_chats_actionIcon))
        floatingButtonIcon?.setDrawBackground(false)
        floatingButtonContainer!!.contentDescription = LocaleController.getString(R.string.Next)
        floatingButtonContainer!!.addView(
            floatingButtonIcon,
            LayoutHelper.createFrame(
                if (Build.VERSION.SDK_INT >= 21) 56 else 60,
                (if (Build.VERSION.SDK_INT >= 21) 56 else 60).toFloat()
            )
        )

        var drawable = Theme.createSimpleSelectorCircleDrawable(
            AndroidUtilities.dp(56f),
            Theme.getColor(Theme.key_chats_actionBackground),
            Theme.getColor(Theme.key_chats_actionPressedBackground)
        )
        if (Build.VERSION.SDK_INT < 21) {
            val shadowDrawable = context.resources.getDrawable(R.drawable.floating_shadow).mutate()
            shadowDrawable.colorFilter = PorterDuffColorFilter(-0x1000000, PorterDuff.Mode.MULTIPLY)
            val combinedDrawable = CombinedDrawable(shadowDrawable, drawable, 0, 0)
            combinedDrawable.setIconSize(AndroidUtilities.dp(56f), AndroidUtilities.dp(56f))
            drawable = combinedDrawable
        }
        floatingButtonContainer!!.background = drawable

        updateFields()
        return fragmentView
    }

    override fun hasForceLightStatusBar(): Boolean = passwordType != SecretPasswordType.CHANGE

    private fun isCustomKeyboardVisible(): Boolean {
        return isPinCode() && !AndroidUtilities.isTablet() &&
            AndroidUtilities.displaySize.x < AndroidUtilities.displaySize.y &&
            !AndroidUtilities.isAccessibilityTouchExplorationEnabled()
    }

    private fun showKeyboard() {
        if (isPinCode()) {
            codeFieldContainer?.codeField?.get(0)?.requestFocus()
            if (!isCustomKeyboardVisible()) {
                AndroidUtilities.showKeyboard(codeFieldContainer?.codeField?.get(0))
            }
        } else if (isPassword()) {
            passwordEditText?.requestFocus()
            AndroidUtilities.showKeyboard(passwordEditText)
        }
    }

    private fun isPinCode(): Boolean {
        return passwordType == SecretPasswordType.SET_NEW && currentPasswordType == SharedConfig.PASSCODE_TYPE_PIN ||
            passwordType == SecretPasswordType.CHANGE && editor.currentType() == SharedConfig.PASSCODE_TYPE_PIN
    }

    private fun isPassword(): Boolean {
        return passwordType == SecretPasswordType.SET_NEW && currentPasswordType == SharedConfig.PASSCODE_TYPE_PASSWORD ||
            passwordType == SecretPasswordType.CHANGE && editor.currentType() == SharedConfig.PASSCODE_TYPE_PASSWORD
    }

    private fun updateFields() {
        val animate = !(descriptionTextSwitcher?.currentView?.let { it as? TextView }?.text.isNullOrEmpty())
        if (passwordType == SecretPasswordType.CHANGE) {
            descriptionTextSwitcher?.setText(LocaleController.getString(R.string.EnterYourPasscodeInfo), animate)
        } else if (passcodeSetStep == 0) {
            descriptionTextSwitcher?.setText(
                LocaleController.getString(if (currentPasswordType == SharedConfig.PASSCODE_TYPE_PIN) R.string.CreatePasscodeInfoPIN else R.string.CreatePasscodeInfoPassword),
                animate
            )
        }
        if (isPinCode()) {
            AndroidUtilities.updateViewVisibilityAnimated(codeFieldContainer, true, 1f, animate)
            AndroidUtilities.updateViewVisibilityAnimated(outlinePasswordView, false, 1f, animate)
        } else if (isPassword()) {
            AndroidUtilities.updateViewVisibilityAnimated(codeFieldContainer, false, 1f, animate)
            AndroidUtilities.updateViewVisibilityAnimated(outlinePasswordView, true, 1f, animate)
        }
        val show = isPassword()
        if (show) {
            onShowKeyboardCallback = Runnable {
                setFloatingButtonVisible(show, animate)
                AndroidUtilities.cancelRunOnUIThread(onShowKeyboardCallback)
            }
            AndroidUtilities.runOnUIThread(onShowKeyboardCallback, 3000)
        } else {
            setFloatingButtonVisible(show, animate)
        }
        setCustomKeyboardVisible(isCustomKeyboardVisible(), animate)
        showKeyboard()
    }

    private fun setFloatingButtonVisible(visible: Boolean, animate: Boolean) {
        floatingButtonAnimator?.cancel()
        floatingButtonAnimator = null
        if (!animate) {
            floatingAutoAnimator?.setOffsetY((if (visible) 0 else AndroidUtilities.dp(70f)).toFloat())
            floatingButtonContainer?.alpha = if (visible) 1f else 0f
            floatingButtonContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        } else {
            val animator = ValueAnimator.ofFloat(
                (if (visible) 0 else 1).toFloat(), (if (visible) 1 else 0).toFloat()
            ).setDuration(150)
            animator.interpolator =
                if (visible) AndroidUtilities.decelerateInterpolator else AndroidUtilities.accelerateInterpolator
            animator.addUpdateListener { animation ->
                val v = animation.animatedValue as Float
                floatingAutoAnimator?.setOffsetY(AndroidUtilities.dp(70f) * (1f - v))
                floatingButtonContainer?.alpha = v
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (visible) floatingButtonContainer?.visibility = View.VISIBLE
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!visible) floatingButtonContainer?.visibility = View.GONE
                    if (floatingButtonAnimator === animation) floatingButtonAnimator = null
                }
            })
            animator.start()
            floatingButtonAnimator = animator
        }
    }

    private fun setCustomKeyboardVisible(visible: Boolean, animate: Boolean) {
        if (visible) {
            AndroidUtilities.hideKeyboard(fragmentView)
            AndroidUtilities.requestAltFocusable(parentActivity, classGuid)
        } else {
            AndroidUtilities.removeAltFocusable(parentActivity, classGuid)
        }

        if (!animate) {
            keyboardView!!.visibility = if (visible) View.VISIBLE else View.GONE
            keyboardView!!.alpha = (if (visible) 1 else 0).toFloat()
            keyboardView!!.translationY =
                (if (visible) 0 else AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat())).toFloat()
            fragmentView.requestLayout()
        } else {
            val animator = ValueAnimator.ofFloat(
                (if (visible) 0 else 1).toFloat(), (if (visible) 1 else 0).toFloat()
            ).setDuration(150)
            animator.interpolator = if (visible) CubicBezierInterpolator.DEFAULT else Easings.easeInOutQuad
            animator.addUpdateListener { animation ->
                val v = animation.animatedValue as Float
                keyboardView!!.alpha = v
                keyboardView!!.translationY =
                    (1f - v) * AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat()) * 0.75f
                fragmentView.requestLayout()
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (visible) keyboardView!!.visibility = View.VISIBLE
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!visible) keyboardView!!.visibility = View.GONE
                }
            })
            animator.start()
        }
    }

    private fun processNext() {
        if (currentPasswordType == SharedConfig.PASSCODE_TYPE_PASSWORD && passwordEditText!!.text.isEmpty() ||
            currentPasswordType == SharedConfig.PASSCODE_TYPE_PIN && codeFieldContainer!!.code.length != 4
        ) {
            onPasscodeError()
            return
        }

        otherItem?.visibility = View.GONE

        titleTextView!!.text = LocaleController.getString(R.string.ConfirmCreatePasscode)
        descriptionTextSwitcher!!.setText(
            AndroidUtilities.replaceTags(LocaleController.getString(R.string.PasscodeReinstallNotice))
        )
        firstPassword = if (isPinCode()) codeFieldContainer!!.code else passwordEditText!!.text.toString()
        passwordEditText!!.setText("")
        passwordEditText!!.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        for (f in codeFieldContainer!!.codeField) f.setText("")
        showKeyboard()
        passcodeSetStep = 1
    }

    private fun onPasscodeError() {
        if (parentActivity == null) return
        try {
            fragmentView.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (ignore: Exception) {
        }
        if (isPinCode()) {
            for (f in codeFieldContainer!!.codeField) {
                f.animateErrorProgress(1f)
            }
        } else {
            outlinePasswordView!!.animateError(1f)
        }
        AndroidUtilities.shakeViewSpring(
            if (isPinCode()) codeFieldContainer else outlinePasswordView,
            (if (isPinCode()) 10 else 4).toFloat()
        ) {
            AndroidUtilities.runOnUIThread({
                if (isPinCode()) {
                    for (f in codeFieldContainer!!.codeField) {
                        f.animateErrorProgress(0f)
                    }
                } else {
                    outlinePasswordView!!.animateError(0f)
                }
            }, (if (isPinCode()) 150 else 1000).toLong())
        }
    }

    private fun processDone() {
        if (isPassword() && passwordEditText!!.text.isEmpty()) {
            onPasscodeError()
            return
        }
        val password = if (isPinCode()) codeFieldContainer!!.code else passwordEditText!!.text.toString()
        if (passwordType == SecretPasswordType.SET_NEW) {
            if (firstPassword != password) {
                AndroidUtilities.updateViewVisibilityAnimated(passcodesDoNotMatchTextView, true)
                for (f in codeFieldContainer!!.codeField) {
                    f.setText("")
                }
                if (isPinCode()) {
                    codeFieldContainer!!.codeField[0].requestFocus()
                }
                passwordEditText!!.setText("")
                onPasscodeError()

                codeFieldContainer!!.removeCallbacks(hidePasscodesDoNotMatch)
                codeFieldContainer!!.post {
                    codeFieldContainer!!.postDelayed(hidePasscodesDoNotMatch, 3000)
                    postedHidePasscodesDoNotMatch = true
                }
                return
            }

            try {
                editor.save(password, currentPasswordType, editor.currentFingerPrint())
            } catch (e: Exception) {
                FileLog.e(e)
            }

            passwordEditText!!.clearFocus()
            AndroidUtilities.hideKeyboard(passwordEditText)
            for (f in codeFieldContainer!!.codeField) {
                f.clearFocus()
                AndroidUtilities.hideKeyboard(f)
            }
            keyboardView!!.setEditText(null)

            // Passcode created → back to the toggle, which now reads as ON.
            animateSuccessAnimation { finishFragment() }
        } else if (passwordType == SecretPasswordType.CHANGE) {
            if (!editor.check(password)) {
                passwordEditText!!.setText("")
                for (f in codeFieldContainer!!.codeField) {
                    f.setText("")
                }
                if (isPinCode()) {
                    codeFieldContainer!!.codeField[0].requestFocus()
                }
                onPasscodeError()
                return
            }

            passwordEditText!!.clearFocus()
            AndroidUtilities.hideKeyboard(passwordEditText)
            for (f in codeFieldContainer!!.codeField) {
                f.clearFocus()
                AndroidUtilities.hideKeyboard(f)
            }
            keyboardView!!.setEditText(null)

            // Passcode confirmed → remove the lock and return; the toggle now reads as OFF.
            animateSuccessAnimation {
                editor.remove()
                finishFragment()
            }
        }
    }

    private fun animateSuccessAnimation(callback: Runnable) {
        if (!isPinCode()) {
            callback.run()
            return
        }
        codeFieldContainer?.let { container ->
            for (i in container.codeField.indices) {
                val field = container.codeField[i]
                field.postDelayed({ field.animateSuccessProgress(1f) }, i * 75L)
            }
            container.postDelayed({
                for (f in container.codeField) {
                    f.animateSuccessProgress(0f)
                }
                callback.run()
            }, container.codeField.size * 75L + 350L)
        }
    }

    override fun onResume() {
        super.onResume()
        if ((passwordType != SecretPasswordType.CHANGE) && !isCustomKeyboardVisible()) {
            AndroidUtilities.runOnUIThread({ this.showKeyboard() }, 200)
        }
        AndroidUtilities.requestAdjustResize(parentActivity, classGuid)

        if (isCustomKeyboardVisible()) {
            AndroidUtilities.hideKeyboard(fragmentView)
            AndroidUtilities.requestAltFocusable(parentActivity, classGuid)
        }
    }

    override fun onPause() {
        super.onPause()
        AndroidUtilities.removeAltFocusable(parentActivity, classGuid)
    }
}
