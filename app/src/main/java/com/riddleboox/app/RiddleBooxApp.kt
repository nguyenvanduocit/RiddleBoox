package com.riddleboox.app

import android.app.Application

/**
 * The Onyx pen SDK reflects into hidden APIs on BOOX firmware. Access to them
 * comes from the required one-time device setup (`adb shell settings put
 * global hidden_api_policy 1` — see "Thiết lập máy BOOX" in README.md): with
 * the default policy ART denies even `VMRuntime.setHiddenApiExemptions`, so no
 * in-app exemption can open the digitizer, and with policy 1 enforcement is
 * off system-wide, so none is needed.
 */
class RiddleBooxApp : Application()
