package com.app.market.data.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 使用平台当前网络的计费状态，包含移动数据和计费 Wi-Fi。 */
internal fun interface NetworkMetering {
    fun isMetered(): Boolean
    val changes: Flow<Boolean> get() = flowOf(isMetered())
}
