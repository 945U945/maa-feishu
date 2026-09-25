package com.aliothmoon.maameow.data.model.update

import androidx.annotation.StringRes
import com.aliothmoon.maameow.R

/** 更新通道 */
enum class UpdateChannel(
    val value: String,
    @param:StringRes val resId: Int
) {
    STABLE("stable", R.string.update_channel_stable),
    BETA("beta", R.string.update_channel_beta)
}

/** 更新源 */
enum class UpdateSource(
    @param:StringRes val resId: Int,
    val type: Int
) {
    GITHUB(R.string.update_source_github, 1),
    MIRROR_CHYAN(R.string.update_source_mirror_chyan, 2)
}
