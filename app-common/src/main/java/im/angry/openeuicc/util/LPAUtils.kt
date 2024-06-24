package im.angry.openeuicc.util

import android.util.Log
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo

const val TAG = "LPAUtils"

val LocalProfileInfo.displayName: String
    get() = nickName.ifEmpty { name }


val LocalProfileInfo.isEnabled: Boolean
    get() = state == LocalProfileInfo.State.Enabled

val List<LocalProfileInfo>.operational: List<LocalProfileInfo>
    get() = filter {
        it.profileClass == LocalProfileInfo.Clazz.Operational
    }

val List<EuiccChannel>.hasMultipleChips: Boolean
    get() = distinctBy { it.slotId }.size > 1

/**
 * Disable the current active profile if any. If refresh is true, also cause a refresh command.
 * See EuiccManager.waitForReconnect()
 */
fun LocalProfileAssistant.disableActiveProfile(refresh: Boolean): Boolean =
    profiles.find { it.isEnabled }?.let {
        Log.i(TAG, "Disabling active profile ${it.iccid}")
        disableProfile(it.iccid, refresh)
    } ?: true

/**
 * Disable the active profile, return a lambda that reverts this action when called.
 * If refreshOnDisable is true, also cause a eUICC refresh command. Note that refreshing
 * will disconnect the eUICC and might need some time before being operational again.
 * See EuiccManager.waitForReconnect()
 */
fun LocalProfileAssistant.disableActiveProfileWithUndo(refreshOnDisable: Boolean): () -> Unit =
    profiles.find { it.isEnabled }?.let {
        disableProfile(it.iccid, refreshOnDisable)
        return { enableProfile(it.iccid) }
    } ?: { }

/**
 * Begin a "tracked" operation where notifications may be generated by the eSIM
 * Automatically handle any newly generated notification during the operation
 * if the function "op" returns true.
 *
 * This requires the EuiccChannelManager object and a slotId / portId instead of
 * just an LPA object, because a LPA might become invalid during an operation
 * that generates notifications. As such, we will end up having to reconnect
 * when this happens.
 *
 * Note that however, if reconnect is required and will not be instant, waiting
 * should be the concern of op() itself, and this function assumes that when
 * op() returns, the slotId and portId will correspond to a valid channel again.
 */
inline fun EuiccChannelManager.beginTrackedOperationBlocking(
    slotId: Int,
    portId: Int,
    op: () -> Boolean
) {
    val latestSeq =
        findEuiccChannelByPortBlocking(slotId, portId)!!.lpa.notifications.firstOrNull()?.seqNumber
            ?: 0
    Log.d(TAG, "Latest notification is $latestSeq before operation")
    if (op()) {
        Log.d(TAG, "Operation has requested notification handling")
        try {
            // Note that the exact instance of "channel" might have changed here if reconnected;
            // so we MUST use the automatic getter for "channel"
            findEuiccChannelByPortBlocking(
                slotId,
                portId
            )?.lpa?.notifications?.filter { it.seqNumber > latestSeq }?.forEach {
                Log.d(TAG, "Handling notification $it")
                findEuiccChannelByPortBlocking(
                    slotId,
                    portId
                )?.lpa?.handleNotification(it.seqNumber)
            }
        } catch (e: Exception) {
            // Ignore any error during notification handling
            e.printStackTrace()
        }
    }
    Log.d(TAG, "Operation complete")
}