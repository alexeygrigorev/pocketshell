package com.pocketshell.app.projects

import com.pocketshell.core.storage.dao.HostDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the folder-tree rename against the exact row generation captured before
 * the gateway suspension. Keeping this async action outside the large tree VM
 * also makes the generation fence visible at the action boundary.
 */
internal fun launchFolderListRename(
    scope: CoroutineScope,
    gateway: FolderListGateway,
    hostDao: HostDao,
    ioDispatcher: CoroutineDispatcher,
    tree: HostTreeModel,
    params: BoundParams,
    oldTarget: String,
    newTarget: String,
    refresh: () -> Unit,
    emitReady: () -> Unit,
    onMissingGeneration: () -> Unit,
    onFailure: (String) -> Unit,
) {
    // Capture before the first suspend point. Resolving the name after the
    // gateway returns could authorize a same-name successor.
    val requestedGeneration = tree.generationForSession(oldTarget) ?: run {
        onMissingGeneration()
        return
    }
    scope.launch {
        val host = withContext(ioDispatcher) { hostDao.getById(params.hostId) } ?: run {
            onFailure("Host not found.")
            return@launch
        }
        val result = gateway.renameSession(
            host = host,
            keyPath = params.keyPath,
            passphrase = params.passphrase,
            oldName = oldTarget,
            newName = newTarget,
            expectedGeneration = requestedGeneration,
        )
        result.fold(
            onSuccess = {
                if (tree.renameSession(requestedGeneration, newTarget)) emitReady()
                refresh()
            },
            onFailure = { error ->
                onFailure(
                    "Couldn't rename $oldTarget: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            },
        )
    }
}
