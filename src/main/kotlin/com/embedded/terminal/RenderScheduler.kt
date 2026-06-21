package com.embedded.terminal

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.jvm.Volatile

class RenderScheduler(
    private val repaintTrigger: () -> Unit
) {
    private var scheduler: ScheduledExecutorService? = null
    
    @Volatile
    private var isPending = false
    
    @Volatile
    private var lastPaintTime = 0L

    fun start() {
        synchronized(this) {
            if (scheduler == null || scheduler!!.isShutdown) {
                scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "TerminalRenderSchedulerThread").apply { isDaemon = true }
                }
            }
        }
    }

    fun requestRepaint() {
        if (isPending) return
        synchronized(this) {
            if (isPending) return
            isPending = true
            
            val now = System.currentTimeMillis()
            val elapsed = now - lastPaintTime
            
            val sched = scheduler
            if (elapsed >= 16 || sched == null || sched.isShutdown) {
                SwingUtilities.invokeLater {
                    isPending = false
                    lastPaintTime = System.currentTimeMillis()
                    repaintTrigger()
                }
            } else {
                val delay = 16L - elapsed
                try {
                    sched.schedule({
                        SwingUtilities.invokeLater {
                            isPending = false
                            lastPaintTime = System.currentTimeMillis()
                            repaintTrigger()
                        }
                    }, delay, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        isPending = false
                        lastPaintTime = System.currentTimeMillis()
                        repaintTrigger()
                    }
                }
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            scheduler?.shutdown()
            try {
                scheduler?.awaitTermination(100, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}
            scheduler = null
        }
    }
}
