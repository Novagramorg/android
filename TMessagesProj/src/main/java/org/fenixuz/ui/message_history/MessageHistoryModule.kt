package org.fenixuz.ui.message_history

class MessageHistoryModule {

    var dialogId: Long? = null
    var msgId: Int? = null

    var messageText: String? = null
    var editedTime: Long? = null

    constructor()

    constructor(dialogId: Long?, msgId: Int?, messageText: String?, editedTime: Long?) {
        this.dialogId = dialogId
        this.msgId = msgId
        this.messageText = messageText
        this.editedTime = editedTime
    }

}