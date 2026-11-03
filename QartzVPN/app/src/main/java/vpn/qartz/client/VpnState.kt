package vpn.qartz.client

import androidx.lifecycle.MutableLiveData

/** Shared, in-process VPN status surfaced to the UI. */
object VpnState {
    enum class Stage { Disconnected, Connecting, Connected }

    val stage = MutableLiveData(Stage.Disconnected)
    val message = MutableLiveData("")

    fun set(stage: Stage, message: String = "") {
        this.stage.postValue(stage)
        this.message.postValue(message)
    }
}
