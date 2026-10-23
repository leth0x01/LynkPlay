package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotTethering.Result

/** 主线程提交/取消请求；每次任务独立失效，避免快速切换模式时旧任务恢复或覆盖新结果。 */
internal class CarHotspotPreparation(
    private val eligible: (Boolean) -> Boolean,
    private val start: (() -> Boolean) -> Result,
    private val dispatch: (() -> Unit) -> Unit,
    private val onResult: (Result) -> Unit,
) {
    @Volatile private var activeRequest: Any? = null

    fun request(wireless: Boolean) {
        if (!eligible(wireless)) {
            cancel()
            return
        }
        if (activeRequest != null) return
        val request = Any()
        activeRequest = request
        Thread({
            val result = start { activeRequest !== request || !eligible(wireless) }
            dispatch {
                if (activeRequest !== request) return@dispatch
                activeRequest = null
                if (result != Result.CANCELLED && eligible(wireless)) onResult(result)
            }
        }, "diplay-hotspot-prepare").start()
    }

    fun cancel() {
        activeRequest = null
    }
}
