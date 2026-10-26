package com.app.market.domain.repository

import com.app.market.domain.exception.MarketException
import kotlinx.coroutines.flow.StateFlow

/** GitHub 访问令牌状态。 */
data class ReleaseTokenState(
    /** 已保存的令牌；空字符串表示未登录。 */
    val token: String = "",
    /** 登录名（GitHub login），未登录时为空。 */
    val login: String = "",
    /** 首次读取是否完成；就绪前 UI 不要当成「未登录」。 */
    val initialized: Boolean = false,
) {
    val loggedIn: Boolean get() = token.isNotBlank()
}

/**
 * GitHub 访问令牌的存取。
 *
 * 匿名调用 api.github.com 只有 60 次/小时的额度，搜索几次就会触发限流（搜索接口更低，仅 10 次/分钟），
 * 带上令牌后提升到 5000 次/小时，因此设置页提供登录入口。
 * 令牌只用于读写 GitHub API，不参与 APK 下载请求，避免跨域重定向时把凭据带到其它主机。
 */
interface ReleaseTokenRepository {
    val state: StateFlow<ReleaseTokenState>

    /**
     * 校验并保存令牌，返回对应的 GitHub 登录名。
     *
     * @throws MarketException 令牌无效、网络不通或 GitHub 返回异常状态时抛出。
     */
    suspend fun login(value: String): String

    /** 清除已保存的令牌，回到匿名访问。 */
    suspend fun logout()

    /**
     * 查询当前令牌的 GitHub API 额度，返回（剩余, 上限）。
     *
     * 未登录时返回匿名额度；查询失败返回 null（额度只是展示信息，失败不影响功能）。
     */
    suspend fun quota(): Pair<Int, Int>?
}
