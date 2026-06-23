package org.fenixuz.utils

import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account.updateStatus

object MyStatus {

    private val TAG = "MyLastOnline"

    fun setMyStatus() {
        //Private or Not MyStatus
        //nobody and everybody
//        var req: TLRPC.TL_account_setPrivacy = TLRPC.TL_account_setPrivacy()
//
//        //nobody and everybody
//        req.key = TLRPC.TL_inputPrivacyKeyStatusTimestamp();
//
//        if (GhostVariable.ghostMode) {
//            //nobody
//            req.rules.add(TLRPC.TL_inputPrivacyValueDisallowAll())
//        } else {
//            //everybody
//            req.rules.add(TLRPC.TL_inputPrivacyValueAllowAll())
//        }
//
//
//        //sendReq
//        ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(
//            req,
//            { response: TLObject, error: TLRPC.TL_error? ->
//                Log.d(TAG, "setMyStatus: $response-$error")
//            }, ConnectionsManager.RequestFlagFailOnServerErrors
//        )



//        setOnlineOrNotMyStatus
        if (GhostVariable.ghostMode) {
            val req = updateStatus()
            req.offline = GhostVariable.ghostMode
            ConnectionsManager.getInstance(UserConfig.selectedAccount)
                .sendRequest(req, { response: TLObject?, error: TLRPC.TL_error? -> })
        }
    }

}