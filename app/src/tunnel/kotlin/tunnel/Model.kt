package tunnel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.ViewGroup
import blocka.RestModel
import blocka.restApi
import com.cloudflare.app.boringtun.BoringTunJNI
import com.google.android.material.snackbar.Snackbar
import core.*
import kotlinx.coroutines.*
import core.displayAccountExpiredNotification
import core.displayLeaseExpiredNotification
import org.blokada.R
import retrofit2.Call
import retrofit2.Response
import java.util.*

typealias MemoryLimit = Int
typealias FilterId = String
typealias Ruleset = LinkedHashSet<String>

data class Filter(
        val id: FilterId,
        val source: FilterSourceDescriptor,
        val whitelist: Boolean = false,
        val active: Boolean = false,
        val hidden: Boolean = false,
        val priority: Int = 0,
        val lastFetch: Time = 0,
        val credit: String? = null,
        val customName: String? = null,
        val customComment: String? = null
) {
    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Filter) return false
        return id == other.id
    }

    override fun toString(): String {
        return id
    }
}

data class FilterStore(
        val cache: Set<Filter> = emptySet(),
        val lastFetch: Time = 0,
        val url: Url = ""
)

fun Set<Filter>.prioritised(): Set<Filter> {
    return toList().sortedBy { it.priority }.mapIndexed { i, f -> f.copy(priority = i) }.toSet()
}

fun List<Filter>.prioritised(): List<Filter> {
    return sortedBy { it.priority }.mapIndexed { i, f -> f.copy(priority = i) }
}

class Memory {
    companion object {
        val linesAvailable = {
            val r = Runtime.getRuntime()
            val free = r.maxMemory() - (r.totalMemory() - r.freeMemory())
            (free / (18 * 2 * 6)).toInt() // (avg chars per host name) * (char size) * (correction)
        }
    }
}

// TODO: better model here
interface IFilterSource {
    fun size(): Int
    fun fetch(): LinkedHashSet<String>
    fun fromUserInput(vararg string: String): Boolean
    fun toUserInput(): String
    fun serialize(): String
    fun deserialize(string: String, version: Int): IFilterSource
    fun id(): String
}

class FilterSourceDescriptor(
        val id: String,
        val source: String
) {
    override fun toString(): String {
        return "$id:$source"
    }
}

// TODO: rename to something else
data class TunnelConfig(
        val wifiOnly: Boolean = true,
        val firstLoad: Boolean = true,
        val powersave: Boolean = false,
        val dnsFallback: Boolean = true,
        val report: Boolean = false,
        val cacheTTL: Long = 86400
): Persistable {
    override fun key() = "tunnel:config"
}

val TUNNEL_CONFIG = "TUNNEL_CONFIG".newEventOf<TunnelConfig>()

fun registerTunnelConfigEvent() {
    val config = runBlocking { TunnelConfig().loadFromPersistence() }
    core.emit(TUNNEL_CONFIG, config)
    core.on(TUNNEL_CONFIG) { runBlocking { it.saveToPersistence() } }
}

val EXPIRATION_OFFSET = 60 * 1000

// TODO: can be null?
data class BlockaConfig(
        val adblocking: Boolean = true,
        val blockaVpn: Boolean = false,
        val accountId: String = "",
        val restoredAccountId: String = "",
        val activeUntil: Date = Date(0),
        val leaseActiveUntil: Date = Date(0),
        val privateKey: String = "",
        val publicKey: String = "",
        val gatewayId: String = "",
        val gatewayIp: String = "",
        val gatewayPort: Int = 0,
        val gatewayNiceName: String = "",
        val vip4: String = "",
        val vip6: String = "",
        val lastDaily: Long = 0L
): Persistable {

    fun getAccountExpiration() = Date(activeUntil.time - EXPIRATION_OFFSET)
    fun getLeaseExpiration() = Date(leaseActiveUntil.time - EXPIRATION_OFFSET)

    fun hasGateway(): Boolean {
        return gatewayId.isNotBlank() && gatewayIp.isNotBlank() && gatewayPort != 0
    }

    override fun toString(): String {
        return "BlockaConfig(adblocking=$adblocking, blockaVpn=$blockaVpn, activeUntil=$activeUntil, leaseActiveUntil=$leaseActiveUntil, publicKey='$publicKey', gatewayId='$gatewayId', gatewayIp='$gatewayIp', gatewayPort=$gatewayPort, vip4='$vip4', vip6='$vip6')"
    }

    override fun key() = "blocka:config"
}

val BLOCKA_CONFIG = "BLOCKA_CONFIG".newEventOf<BlockaConfig>()

fun registerBlockaConfigEvent() {
    val config = runBlocking { BlockaConfig().loadFromPersistence() }

    v("loading boringtun")
    System.loadLibrary("boringtun")
    v("boringtun loaded")

    checkAccountInfo(config)

    core.on(BLOCKA_CONFIG, { runBlocking { it.saveToPersistence() } })

    device.screenOn.doOnUiWhenChanged().then {
        if (device.screenOn()) GlobalScope.async {
            core.getMostRecent(BLOCKA_CONFIG)?.run {
                if (!DateUtils.isToday(lastDaily)) {
                    v("daily check account")
                    checkAccountInfo(copy(lastDaily = System.currentTimeMillis()))
                }
            }
        }
    }
}

val MAX_RETRIES = 3
private fun newAccount(config: BlockaConfig, retry: Int = 0) {
    restApi.newAccount().enqueue(object: retrofit2.Callback<RestModel.Account> {
        override fun onFailure(call: Call<RestModel.Account>?, t: Throwable?) {
            e("new account api call error", t ?: "null")
            if (retry < MAX_RETRIES) newAccount(config, retry + 1)
            else clearConnectedGateway(config)
        }

        override fun onResponse(call: Call<RestModel.Account>?, response: Response<RestModel.Account>?) {
            response?.run {
                when (code()) {
                    200 -> {
                        body()?.run {
                            val secret = BoringTunJNI.x25519_secret_key()
                            val public = BoringTunJNI.x25519_public_key(secret)
                            val newCfg = config.copy(
                                    accountId = account.accountId,
                                    privateKey = BoringTunJNI.x25519_key_to_base64(secret),
                                    publicKey = BoringTunJNI.x25519_key_to_base64(public)
                            )
                            core.emit(BLOCKA_CONFIG, newCfg)
                            v("new user. public key: ${newCfg.publicKey}")
                        }
                    }
                    else -> {
                        e("new account api call response ${code()}")
                        if (retry < MAX_RETRIES) newAccount(config, retry + 1)
                        else clearConnectedGateway(config)
                    }
                }
            }
        }
    })
}

// To prevent request loop
private var requestsSince = 0L
private var requests = 0

fun checkAccountInfo(config: BlockaConfig, retry: Int = 0, showError: Boolean = false) {
    if (requestsSince + 10 * 1000 < System.currentTimeMillis()) {
        // 10 seconds passed, its ok to make requests
        requestsSince = System.currentTimeMillis()
        requests = 0
    }

    if (++requests > 10) {
        e("too many check account requests recently, disabling vpn")
        clearConnectedGateway(config)
        requests = 0
        return
    }

    if (config.accountId.isBlank()) {
        v("accountId not set, creating new account")
        newAccount(config)
        return
    }

    v("check account api call")

    val accountId = if (config.restoredAccountId.isBlank()) config.accountId else config.restoredAccountId
    restApi.getAccountInfo(accountId).enqueue(object: retrofit2.Callback<RestModel.Account> {
        override fun onFailure(call: Call<RestModel.Account>?, t: Throwable?) {
            e("check account api call error", t ?: "null")
            if (retry < MAX_RETRIES) checkAccountInfo(config, retry + 1, showError)
            else {
                if (showError) GlobalScope.launch { showSnack(R.string.slot_account_name_api_error) }
                clearConnectedGateway(config, showError = false)
            }
        }

        override fun onResponse(call: Call<RestModel.Account>?, response: Response<RestModel.Account>?) {
            response?.run {
                when (code()) {
                    200 -> {
                        body()?.run {
                            val newCfg = if (config.restoredAccountId.isBlank()) config.copy(
                                    activeUntil = account.activeUntil
                            ) else {
                                v("restored account id")
                                config.copy(
                                    activeUntil = account.activeUntil,
                                    accountId = config.restoredAccountId,
                                    restoredAccountId = ""
                                )
                            }
                            if (!account.expiresSoon()) {
                                v("current account active until: ${newCfg.activeUntil}")
                                checkGateways(newCfg)
                            } else {
                                v("current account inactive")
                                if (newCfg.blockaVpn) {
                                    displayAccountExpiredNotification()
                                    GlobalScope.launch { showSnack(R.string.account_inactive) }
                                }
                                clearConnectedGateway(newCfg, showError = false)
                            }
                        }
                    }
                    else -> {
                        e("check account api call response ${code()}")
                        if (retry < MAX_RETRIES) checkAccountInfo(config, retry + 1, showError)
                        else {
                            if (showError) GlobalScope.launch { showSnack(R.string.slot_account_name_api_error) }
                            clearConnectedGateway(config, showError = false)
                        }
                        Unit
                    }
                }
            }
        }
    })
}

suspend fun showSnack(msgResId: Int) = withContext(Dispatchers.Main.immediate) {
    val view = getActivity()?.findViewById<ViewGroup>(R.id.root)
    view?.run {
        val snack = Snackbar.make(this, msgResId, 5000)
        snack.view.setBackgroundResource(R.drawable.snackbar)
        snack.view.setPadding(32, 32, 32, 32)
        snack.setAction(R.string.menu_ok, { snack.dismiss() })
        snack.show()
    }
}

suspend fun showSnack(resource: Resource) = withContext(Dispatchers.Main.immediate) {
    val view = getActivity()?.findViewById<ViewGroup>(R.id.root)
    view?.run {
        if (resource.hasResId()) showSnack(resource.getResId())
        else {
            val snack = Snackbar.make(this, resource.getString(), 5000)
            snack.view.setBackgroundResource(R.drawable.snackbar)
            snack.view.setPadding(32, 32, 32, 32)
            snack.setAction(R.string.menu_ok, { snack.dismiss() })
            snack.show()
        }
    }
}

fun clearConnectedGateway(config: BlockaConfig, showError: Boolean = true) {
    v("clearing connected gateway")
    if (config.blockaVpn && showError) {
        displayLeaseExpiredNotification()
        GlobalScope.launch { showSnack(R.string.slot_lease_cant_connect) }
    } else if (config.accountId.isBlank() && showError) {
        GlobalScope.launch { showSnack(R.string.slot_account_cant_create) }
    }

    deleteLease(config)
    core.emit(BLOCKA_CONFIG, config.copy(
            blockaVpn = false,
            gatewayId = "",
            gatewayIp = "",
            gatewayPort = 0,
            gatewayNiceName = ""
    ))
}

fun checkGateways(config: BlockaConfig, retry: Int = 0) {
    v("check gateway api call")
    restApi.getGateways().enqueue(object: retrofit2.Callback<RestModel.Gateways> {
        override fun onFailure(call: Call<RestModel.Gateways>?, t: Throwable?) {
            e("gateways api call error", t ?: "null")
            if (retry < MAX_RETRIES) checkGateways(config, retry + 1)
            else clearConnectedGateway(config)
        }

        override fun onResponse(call: Call<RestModel.Gateways>?, response: Response<RestModel.Gateways>?) {
            response?.run {
                when (code()) {
                    200 -> {
                        body()?.run {
                            val gateway = gateways.firstOrNull { it.publicKey == config.gatewayId }
                            if (gateway != null) {
                                val newCfg = config.copy(
                                        gatewayId = gateway.publicKey,
                                        gatewayIp = gateway.ipv4,
                                        gatewayPort = gateway.port,
                                        gatewayNiceName = gateway.niceName()
                                )
                                v("found gateway, chosen: ${newCfg.gatewayId}")
                                checkLease(newCfg)
                            } else {
                                v("found no matching gateway")
                                clearConnectedGateway(config)
                            }
                        }
                    }
                    else -> {
                        e("gateways api call response ${code()}")
                        if (retry < MAX_RETRIES) checkGateways(config, retry + 1)
                        else clearConnectedGateway(config)
                        Unit
                    }
                }
            }
        }
    })
}

fun checkLeaseIfNeeded() {
    GlobalScope.async {
        core.getMostRecent(BLOCKA_CONFIG)?.run {
            if (leaseActiveUntil.before(Date())) checkLease(this)
        }
    }
}

private fun checkLease(config: BlockaConfig, retry: Int = 0) {
    v("check lease api call")
    restApi.getLeases(config.accountId).enqueue(object: retrofit2.Callback<RestModel.Leases> {
        override fun onFailure(call: Call<RestModel.Leases>?, t: Throwable?) {
            e("leases api call error", t ?: "null")
            if (retry < MAX_RETRIES) checkLease(config, retry + 1)
            else clearConnectedGateway(config)
        }

        override fun onResponse(call: Call<RestModel.Leases>?, response: Response<RestModel.Leases>?) {
            response?.run {
                when (code()) {
                    200 -> {
                        body()?.run {
                            // User might have a lease for old private key (if restoring account)
                            val obsoleteLeases = leases.filter { it.publicKey != config.publicKey }
                            obsoleteLeases.forEach { deleteLease(config.copy(
                                    publicKey = it.publicKey,
                                    gatewayId = it.gatewayId
                            )) }
                            if (obsoleteLeases.isNotEmpty()) GlobalScope.launch { showSnack(R.string.slot_lease_deleted_information) }

                            val lease = leases.firstOrNull {
                                it.publicKey == config.publicKey && it.gatewayId == config.gatewayId
                            }
                            if (lease != null && !lease.expiresSoon()) {
                                val newCfg = config.copy(
                                        vip4 = lease.vip4,
                                        vip6 = lease.vip6,
                                        leaseActiveUntil = lease.expires,
                                        blockaVpn = true
                                )
                                v("found active lease until: ${lease.expires}")
                                core.emit(BLOCKA_CONFIG, newCfg)
                                scheduleRecheck(newCfg)
                            } else {
                                v("no active lease, or expires soon")
                                newLease(config)
                            }
                        }
                    }
                    else -> {
                        e("leases api call response ${code()}")
                        if (retry < MAX_RETRIES) checkLease(config, retry + 1)
                        else clearConnectedGateway(config)
                        Unit
                    }
                }
            }
        }
    })
}

private fun deleteLease(config: BlockaConfig, retry: Int = 0) {
    if (config.gatewayId.isBlank()) return

    // TODO: rewrite it to sync version, or use callback on finish
    restApi.deleteLease(RestModel.LeaseRequest(config.accountId, config.publicKey, config.gatewayId)).enqueue(object: retrofit2.Callback<Void> {

        override fun onFailure(call: Call<Void>?, t: Throwable?) {
            e("delete lease api call error", t ?: "null")
            if (retry < MAX_RETRIES) deleteLease(config, retry + 1)
        }

        override fun onResponse(call: Call<Void>?, response: Response<Void>?) {
            e("obsolete lease deleted")
        }

    })
}

private fun newLease(config: BlockaConfig, retry: Int = 0) {
    v("new lease api call")

    restApi.newLease(RestModel.LeaseRequest(config.accountId, config.publicKey, config.gatewayId)).enqueue(object: retrofit2.Callback<RestModel.Lease> {
        override fun onFailure(call: Call<RestModel.Lease>?, t: Throwable?) {
            e("new lease api call error", t ?: "null")
            if (retry < MAX_RETRIES) newLease(config, retry + 1)
            else clearConnectedGateway(config)
        }

        override fun onResponse(call: Call<RestModel.Lease>?, response: Response<RestModel.Lease>?) {
            response?.run {
                when (code()) {
                    200 -> {
                        body()?.run {
                            val newCfg = config.copy(
                                    vip4 = lease.vip4,
                                    vip6 = lease.vip6,
                                    leaseActiveUntil = lease.expires,
                                    blockaVpn = true
                            )
                            v("new active lease, until: ${lease.expires}")
                            core.emit(BLOCKA_CONFIG, newCfg)
                            scheduleRecheck(newCfg)
                        }
                    }
                    else -> {
                        e("new lease api call response ${code()}")
                        if (retry < MAX_RETRIES) newLease(config, retry + 1)
                        else clearConnectedGateway(config)
                        Unit
                    }
                }
            }
        }
    })
}

private fun scheduleRecheck(config: BlockaConfig) {
    val ctx = runBlocking { getApplicationContext()!! }
    val alarm: AlarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    val operation = Intent(ctx, RenewLicenseReceiver::class.java).let { intent ->
        PendingIntent.getBroadcast(ctx, 0, intent, 0)
    }

    val accountTime = config.getAccountExpiration()
    val leaseTime = config.getLeaseExpiration()
    val sooner = if (accountTime.before(leaseTime)) accountTime else leaseTime
    if (sooner.before(Date())) {
        core.emit(BLOCKA_CONFIG, config.copy(blockaVpn = false))
        GlobalScope.async {
            // Wait until tunnel is off and recheck
            delay(3000)
            checkAccountInfo( config)
        }
    } else {
        alarm.set(AlarmManager.RTC, sooner.time, operation)
        v("scheduled account / lease recheck for $sooner")
    }
}

class RenewLicenseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, p1: Intent) {
        GlobalScope.async {
            v("recheck account / lease task executing")
            val config = core.getMostRecent(BLOCKA_CONFIG)!!
            checkAccountInfo(config)
        }
    }
}

data class Request(
        val domain: String,
        val blocked: Boolean = false,
        val time: Date = Date()
) {
    override fun equals(other: Any?): Boolean {
        return if (other !is Request) false
        else domain.equals(other.domain)
    }

    override fun hashCode(): Int {
        return domain.hashCode()
    }
}
