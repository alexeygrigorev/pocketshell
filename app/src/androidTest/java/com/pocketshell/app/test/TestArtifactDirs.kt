package com.pocketshell.app.test

import android.content.Context
import java.io.File

fun testArtifactsRoot(context: Context): File =
    context.getExternalFilesDir(null) ?: context.filesDir
