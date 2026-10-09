package com.app.market.data.install.backend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.app.market.data.install.InstallPipelineException
import com.app.market.domain.model.install.InstallFailureCode
import com.app.market.domain.model.installer.InstallerMode

internal class StandardInstallerBackend(
    private val context: Context,
) : InstallerBackend {
    override val mode: InstallerMode = InstallerMode.STANDARD

    override suspend fun open(): PackageInstallerAccess {
        val hasPrivilegedPermission =
            context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
        if (!hasPrivilegedPermission && !context.packageManager.canRequestPackageInstalls()) {
            throw InstallPipelineException(
                InstallFailureCode.UNKNOWN_SOURCES_PERMISSION,
                "Allow AppHarbor to install unknown apps before continuing",
            )
        }
        return PackageInstallerAccess(
            packageInstaller = context.packageManager.packageInstaller,
            installerPackageName = context.packageName,
        )
    }
}
