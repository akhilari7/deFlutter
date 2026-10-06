package com.payatu.flutterinspect.server

import com.payatu.flutterinspect.data.models.HookTargetConfig
import com.payatu.flutterinspect.data.symbols.SymbolManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * Central event bus and in-memory log buffer for FlutterInspect.
 * Dispatches intercepted calls to Compose Live Console and Ktor Web Terminal simultaneously.
 */
object TraceLogBroadcaster {

    private val _logsFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1000)
    val logsFlow: SharedFlow<String> = _logsFlow.asSharedFlow()

    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_BUFFER_SIZE = 2500

    // Tracer state visible to Web Clients & REST API
    private val _activeTarget = MutableStateFlow("")
    val activeTarget: StateFlow<String> = _activeTarget.asStateFlow()

    private val _isHookRunning = MutableStateFlow(false)
    val isHookRunning: StateFlow<Boolean> = _isHookRunning.asStateFlow()

    private val _activeEngine = MutableStateFlow("DOBBY") // "DOBBY" or "FRIDA"
    val activeEngine: StateFlow<String> = _activeEngine.asStateFlow()

    private val _activeHooks = MutableStateFlow<List<HookTargetConfig>>(emptyList())
    val activeHooks: StateFlow<List<HookTargetConfig>> = _activeHooks.asStateFlow()

    fun updateTarget(target: String) {
        _activeTarget.value = target
    }

    fun updateHookState(running: Boolean, engine: String = _activeEngine.value) {
        _isHookRunning.value = running
        _activeEngine.value = engine
    }

    fun updateActiveHooks(hooks: List<HookTargetConfig>) {
        _activeHooks.value = hooks
    }

    fun postLog(rawOrDecoratedLine: String) {
        val decorated = SymbolManager.decorateLogLine(rawOrDecoratedLine)

        synchronized(logBuffer) {
            logBuffer.add(decorated)
            if (logBuffer.size > MAX_BUFFER_SIZE) {
                logBuffer.removeAt(0)
            }
        }
        _logsFlow.tryEmit(decorated)
    }

    fun redecorateBuffer() {
        synchronized(logBuffer) {
            for (i in logBuffer.indices) {
                logBuffer[i] = SymbolManager.decorateLogLine(logBuffer[i])
            }
        }
    }

    fun getRecentLogs(): List<String> {
        return synchronized(logBuffer) {
            logBuffer.toList()
        }
    }

    fun clear() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
        _logsFlow.resetReplayCache()
        _logsFlow.tryEmit("__CLEAR_LOGS__")
    }
}
