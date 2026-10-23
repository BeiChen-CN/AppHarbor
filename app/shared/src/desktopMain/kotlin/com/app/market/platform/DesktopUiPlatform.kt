package com.app.market.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class DesktopUiPlatform : UiPlatform {
    override val packageInstallationSupported: Boolean = false

    private var lastClipboardText: String? = null

    override suspend fun readNewClipboardText(): String? {
        val text = runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        }.getOrNull() ?: return null
        if (text == lastClipboardText) return null
        lastClipboardText = text
        return text
    }

    override fun shareText(text: String): ShareResult = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        lastClipboardText = text
        ShareResult.Copied
    }.getOrDefault(ShareResult.Failed)

    override fun showToast(message: String) {
        println("[toast] $message")
    }

    override fun openAppSettings(): Boolean = false

    override fun openUnknownSourcesSettings(): Boolean = false

    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit): Boolean = false

    override fun requestPostNotificationsPermission(onResult: () -> Unit): Boolean {
        onResult()
        return true
    }

    override suspend fun saveImageToPictures(url: String, fileName: String): ImageSaveResult =
        ImageSaveResult.Unsupported
}
