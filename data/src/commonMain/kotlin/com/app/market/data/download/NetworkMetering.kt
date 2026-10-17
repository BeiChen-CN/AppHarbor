package com.app.market.data.download

/** 使用平台当前网络的计费状态，包含移动数据和计费 Wi-Fi。 */
internal fun interface NetworkMetering {
    fun isMetered(): Boolean
}
