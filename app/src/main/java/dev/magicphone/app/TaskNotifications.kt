// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.magicphone.core.RunState

object TaskNotifications {
    const val ID = 7
    const val CHANNEL = "task"
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastPosted = 0L
    private var shown: Pair<RunState, InputRequest?>? = null

    fun enabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(context: Context) {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        shown = null
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun update(context: Context, state: RunState, request: InputRequest? = null) {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        val manager = context.getSystemService(NotificationManager::class.java)
        val snapshot = state to request
        if (shown == snapshot && manager.activeNotifications.any { it.id == ID }) return
        // Android can drop bursts of notification updates. Keep the newest state, and bound
        // posting frequency so a newly arrived question cannot be lost behind tool statuses.
        val delay = (500 - (SystemClock.elapsedRealtime() - lastPosted)).coerceAtLeast(0)
        val app = context.applicationContext
        val dispatch = Runnable {
            pending = null
            post(app, state, request)
            shown = snapshot
            lastPosted = SystemClock.elapsedRealtime()
        }
        pending = dispatch
        if (delay == 0L) dispatch.run() else handler.postDelayed(dispatch, delay)
    }

    private fun post(context: Context, state: RunState, request: InputRequest?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.task_controls), NotificationManager.IMPORTANCE_LOW))
        if (!enabled(context)) return
        fun control(action: String) = PendingIntent.getBroadcast(context, action.hashCode(),
            Intent(context, ControlReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, MainActivity.chatIntent(context, request?.conversation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_magic)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(request?.message?.take(240) ?: context.getString(stateResource(state)))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
        if (request != null) {
            notification.setSubText(context.getString(R.string.input_bubble_title))
                .setStyle(Notification.BigTextStyle().bigText(request.message.take(2000)))
                .addAction(Notification.Action.Builder(null,
                    context.getString(R.string.input_bubble_reply), open).build())
        }
        if (state !in setOf(RunState.IDLE, RunState.COMPLETED, RunState.FAILED,
                RunState.STOPPED, RunState.INTERRUPTED)) {
            val paused = state == RunState.PAUSED
            notification.addAction(Notification.Action.Builder(null,
                context.getString(if (paused) R.string.resume else R.string.pause),
                control(if (paused) "resume" else "pause")).build())
            notification.addAction(Notification.Action.Builder(null,
                context.getString(R.string.stop), control("stop")).build())
        }
        try {
            manager.notify(ID, notification.build())
        } catch (_: SecurityException) {
            // Permission may be revoked between checking it and posting. In-app Stop stays available.
        }
    }
}
