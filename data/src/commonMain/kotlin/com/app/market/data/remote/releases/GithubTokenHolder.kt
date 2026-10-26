package com.app.market.data.remote.releases

/**
 * 进程内的 GitHub 令牌快照。
 *
 * [ReleaseApi] 与令牌仓储互相需要（一个发请求、一个校验后落盘），用这个可变持有者打断构造期循环：
 * 仓储构造完就把值写进来，之后每个请求同步读取即可，不用挂起查询。
 */
internal class GithubTokenHolder {
    @Volatile
    var token: String = ""
}
