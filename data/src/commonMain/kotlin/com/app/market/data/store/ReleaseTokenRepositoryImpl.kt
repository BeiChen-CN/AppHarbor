package com.app.market.data.store

import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.releases.GithubTokenHolder
import com.app.market.data.remote.releases.ReleaseApi
import com.app.market.domain.repository.ReleaseTokenRepository
import com.app.market.domain.repository.ReleaseTokenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GitHub 令牌持久化。
 *
 * 令牌存在 release_sources 命名空间下的 `github.token`，登录名另存一份，
 * 这样冷启动时不用再打一次网络就能显示「已登录 xxx」。
 * 读出的令牌同时写进 [GithubTokenHolder]，让 [ReleaseApi] 每个请求都能同步拿到。
 */
internal class ReleaseTokenRepositoryImpl(
    private val preferences: PreferencesDataSource,
    private val api: ReleaseApi,
    private val holder: GithubTokenHolder,
    scope: CoroutineScope,
) : ReleaseTokenRepository {
    private val mutableState = MutableStateFlow(ReleaseTokenState())
    override val state: StateFlow<ReleaseTokenState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val token = preferences.read(Token).orEmpty().trim()
            val login = preferences.read(Login).orEmpty().trim()
            holder.token = token
            mutableState.value = ReleaseTokenState(token = token, login = login, initialized = true)
        }
    }

    override suspend fun login(value: String): String {
        val candidate = value.trim()
        require(candidate.isNotEmpty()) { "token must not be blank" }
        // 先校验再落盘：无效令牌不写入偏好，避免之后所有 GitHub 请求都变成 401。
        val login = api.viewerLogin(candidate)
        preferences.put(Token, candidate)
        preferences.put(Login, login)
        holder.token = candidate
        mutableState.value = ReleaseTokenState(token = candidate, login = login, initialized = true)
        return login
    }

    override suspend fun logout() {
        preferences.remove(Token)
        preferences.remove(Login)
        holder.token = ""
        mutableState.value = ReleaseTokenState(initialized = true)
    }

    override suspend fun quota(): Pair<Int, Int>? = api.rateLimit(mutableState.value.token)

    private companion object {
        val Token = StringPreferenceKey("release_sources", "github.token")
        val Login = StringPreferenceKey("release_sources", "github.login")
    }
}
