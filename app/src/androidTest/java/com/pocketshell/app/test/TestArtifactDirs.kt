package com.pocketshell.app.test

import android.content.Context
import java.io.File

@Suppress("DEPRECATION")
fun testArtifactsRoot(context: Context): File =
    context.getExternalMediaDirs().firstOrNull()
        ?: context.getExternalFilesDir(null)
        ?: context.filesDir
