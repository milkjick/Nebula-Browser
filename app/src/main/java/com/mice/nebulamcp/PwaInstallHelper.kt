package com.mice.nebulamcp

import android.content.Context
import android.content.Intent
import android.net.Uri

object PwaInstallHelper {
    fun addShortcut(context: Context, title: String, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
