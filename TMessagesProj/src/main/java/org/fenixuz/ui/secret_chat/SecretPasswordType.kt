package org.fenixuz.ui.secret_chat

enum class SecretPasswordType {
    /** First-time creation of a secret-folder passcode. */
    SET_NEW,

    /** Re-enter the existing passcode before changing it. */
    CHANGE
}
