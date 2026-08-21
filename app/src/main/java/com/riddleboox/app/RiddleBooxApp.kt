package com.riddleboox.app

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The Onyx pen SDK reaches hidden APIs on BOOX firmware; both Inka and the
 * official OnyxAndroidDemo exempt hidden API access before touching the SDK,
 * otherwise raw pen input never reaches TouchHelper.
 */
class RiddleBooxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
    }
}