package notification

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.widget.Toast
import com.github.salomonbrys.kodein.instance
import core.Tunnel
import gs.environment.inject
import org.blokada.R


class ANotificationsToggleService : IntentService("notificationsToggle") {
    private var mHandler: Handler = Handler()

    override fun onHandleIntent(intent: Intent) {
        val t: Tunnel = this.inject().instance()
        t.enabled %= intent.getBooleanExtra("new_state", true)
        if(intent.getBooleanExtra("new_state", true)){
            mHandler.post(DisplayToastRunnable(this, this.resources.getString(R.string.notification_keepalive_activating)))
        }else{
            mHandler.post(DisplayToastRunnable(this, this.resources.getString(R.string.notification_keepalive_deactivating)))
        }
    }

}

class DisplayToastRunnable(private val mContext: Context, private var mText: String) : Runnable {
    override fun run() {
        Toast.makeText(mContext, mText, Toast.LENGTH_SHORT).show()
    }
}
