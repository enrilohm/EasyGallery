package com.example.easygallery

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Tracks the live MainActivity / ImageDetailActivity instances so that starting a
 * new similarity search can collapse the older chain instead of stacking forever.
 *
 * Each similarity search launches a fresh MainActivity on top of the
 * ImageDetailActivity it was started from. Left unchecked this produces an
 * unbounded MainActivity -> ImageDetailActivity -> MainActivity -> ... back stack,
 * every MainActivity keeping all of its tab fragments alive (memory growth).
 *
 * When a new search starts we keep only:
 *   - the task-root MainActivity (the original gallery), so Back still lands there
 *   - the ImageDetailActivity the search was started from (the "last selection")
 *   - the new MainActivity (not yet registered when the collapse runs)
 * and finish everything in between.
 */
object SimilarNav {
    private val live = mutableListOf<WeakReference<Activity>>()

    fun register(activity: Activity) {
        synchronized(live) {
            live.removeAll { it.get() == null }
            if (live.none { it.get() === activity }) {
                live.add(WeakReference(activity))
            }
        }
    }

    fun unregister(activity: Activity) {
        synchronized(live) {
            live.removeAll { ref -> ref.get().let { it == null || it === activity } }
        }
    }

    /**
     * Finish every tracked activity except [keep] and the task root, capping the
     * back stack at [task root] -> [keep] -> (the new search results).
     */
    fun collapseExcept(keep: Activity) {
        val toFinish = synchronized(live) {
            live.mapNotNull { it.get() }
                .filter { it !== keep && !it.isTaskRoot && !it.isFinishing }
        }
        toFinish.forEach { it.finish() }
    }
}
