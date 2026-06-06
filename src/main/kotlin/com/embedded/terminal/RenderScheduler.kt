package com.embedded.terminal

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class RenderScheduler(
    private val repaintTrigger: () -> Unit
) {
    private var scheduler: ScheduledExecutorService? = null
    private var future: ScheduledFuture<*>? = null
    
    @Volatile
    private var hasPendingRepaint = false

    fun start() {
        synchronized(this) {
            if (scheduler == null || scheduler!!.isShutdown) {
                scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "TerminalRenderSchedulerThread").apply { isDaemon = true }
                }
            }
            if (future == null || future!!.isCancelled || future!!.isDone) {
                future = scheduler!!.scheduleAtFixedRate({
                    if (hasPendingRepaint) {
                        hasPendingRepaint = false
                        SwingUtilities.invokeLater {
                            repaintTrigger()
                        }
                    }
                }, 0, 16666, TimeUnit.MICROSECONDS) // 60Hz = ~16.6ms
            }
        }
    }

    fun requestRepaint() {
        hasPendingRepaint = true
    }

    fun shutdown() {
        synchronized(this) {
            future?.cancel(true)
            future = null
            scheduler?.shutdown()
            try {
                scheduler?.awaitTermination(100, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}
            scheduler = null
        }
    }
}
