package com.aliothmoon.maameow.utils.i18n

import android.content.Context
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.domain.models.OverlayControlMode
import com.aliothmoon.maameow.domain.models.RemoteBackend
import com.aliothmoon.maameow.domain.models.RunMode

fun Context.runModeDisplayName(mode: RunMode): String {
    return when (mode) {
        RunMode.FOREGROUND -> getString(R.string.home_run_mode_foreground)
        RunMode.BACKGROUND -> getString(R.string.home_run_mode_background)
    }
}

fun Context.overlayControlModeDisplayName(mode: OverlayControlMode): String {
    return when (mode) {
        OverlayControlMode.ACCESSIBILITY -> getString(R.string.home_overlay_mode_accessibility)
        OverlayControlMode.FLOAT_BALL -> getString(R.string.home_overlay_mode_float_ball)
    }
}

fun Context.remoteBackendPermissionLabel(backend: RemoteBackend): String {
    return getString(R.string.remote_backend_permission_label, backend.display)
}
