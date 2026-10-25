package com.app.market.domain.repository

import com.app.market.domain.model.update.AppUpdateInfo

/** Checks the project's own GitHub Releases feed for a newer Trove build. */
fun interface AppUpdateRepository {
    suspend fun latest(): AppUpdateInfo?
}
