/*
 *  Copyright (c) 2026 AnkiDroid Contributors
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ichi2.anki.shouldFetchMedia
import com.ichi2.anki.speedrun.SpeedrunDb
import com.ichi2.anki.speedrun.SpeedrunFirestoreSync
import com.ichi2.anki.syncAuth
import com.ichi2.anki.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Triggers a background sync whenever validated internet connectivity is (re)established.
 *
 * Design rationale
 * ----------------
 * Rather than tracking an "was offline" flag (which has subtle race conditions when the device
 * switches between wifi and cellular), we use a simple cooldown approach:
 *
 * - [onLost] resets the cooldown to zero so the very next validated network event always syncs.
 * - [onCapabilitiesChanged] fires once [NetworkCapabilities.NET_CAPABILITY_VALIDATED] is set,
 *   meaning Android has confirmed real internet access (unlike [onAvailable], which fires as soon
 *   as the link-layer connects — before validation).
 * - A [SYNC_COOLDOWN_MS] guard prevents re-syncing on routine capability updates (signal changes
 *   etc.) while the cooldown is still active.
 * - [SyncWorker] uses [androidx.work.ExistingWorkPolicy.KEEP], so redundant calls are safe.
 *
 * It also pushes any speedrun performance records that failed to reach Firestore while offline.
 */
class NetworkConnectivityObserver(
    private val context: Context,
) : ConnectivityManager.NetworkCallback() {
    /**
     * Set to true by [onLost]; cleared once [onCapabilitiesChanged] fires a validated network
     * after the loss.  Starts false so the initial registration callback (which fires for the
     * already-active network) does NOT trigger a spurious sync.
     */
    private var disconnectedSinceLastSync = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        if (!disconnectedSinceLastSync) return
        disconnectedSinceLastSync = false
        Timber.i("connectivity: network validated after disconnect — signalling DeckPicker")
        lastReconnectMs.value = System.currentTimeMillis()
        maybeBackgroundSync()
        pushUnsyncedSpeedrunRecords()
    }

    override fun onLost(network: Network) {
        Timber.d("connectivity: network lost")
        disconnectedSinceLastSync = true
    }

    /** Background-only fallback sync for when DeckPicker is not in the foreground. */
    private fun maybeBackgroundSync() {
        if (MeteredSyncPolicy.shouldBlock()) return
        val auth = syncAuth() ?: return
        Timber.i("connectivity: firing background sync fallback")
        SyncWorker.start(context, auth, shouldFetchMedia())
    }

    /** Push any speedrun records that were saved locally while offline. */
    private fun pushUnsyncedSpeedrunRecords() {
        if (!SpeedrunFirestoreSync.isConfigured) return
        scope.launch {
            val unsynced = SpeedrunDb.unsyncedRecords(context)
            if (unsynced.isEmpty()) return@launch
            Timber.i("connectivity: pushing %d unsynced speedrun records", unsynced.size)
            for (record in unsynced) {
                if (SpeedrunFirestoreSync.push(record)) {
                    SpeedrunDb.markSynced(context, record.syncKey)
                }
            }
        }
    }

    companion object {
        /**
         * Timestamp (ms) of the most recent validated-network reconnect event.
         * [DeckPicker] observes this to trigger an immediate foreground sync when it is in the
         * RESUMED state, giving the user visible feedback rather than a silent background job.
         * Starts at 0 so DeckPicker's initial startup doesn't receive a spurious reconnect.
         */
        val lastReconnectMs = MutableStateFlow(0L)
    }
}
