package com.photoconnect.utils

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.photoconnect.R

object AppTourManager {
    enum class Role(val key: String) {
        CLIENT("client"),
        CREATOR("creator"),
        GUEST("guest"),
    }

    private const val PREFS = "app_tour_preferences"
    private const val TOUR_VERSION = 5
    private const val OVERLAY_TAG = "app_tour_overlay"

    data class TourStep(
        @param:StringRes val title: Int,
        @param:StringRes val body: Int,
        @param:IdRes val targetId: Int? = null,
        @param:IdRes val tabId: Int? = targetId,
    )

    fun showIfNeeded(activity: AppCompatActivity, role: Role, identity: String = "") {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = doneKey(role, identity)
        if (prefs.getBoolean(key, false)) return
        activity.window.decorView.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                show(activity, role, identity, force = false)
            }
        }, 500L)
    }

    fun show(activity: Activity, role: Role, identity: String = "", force: Boolean = true) {
        if (activity.isFinishing || activity.isDestroyed) return
        val steps = stepsFor(role)
        if (steps.isEmpty()) return

        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
        if (contentView.findViewWithTag<View>(OVERLAY_TAG) != null) return
        val overlayView = AppTourOverlayView(
            context = activity,
            activity = activity,
            steps = steps,
            onComplete = {
                if (!force) markDone(activity, role, identity)
            }
        )
        overlayView.tag = OVERLAY_TAG
        contentView.addView(overlayView)
        overlayView.start()
    }

    private fun markDone(context: Context, role: Role, identity: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(doneKey(role, identity), true)
            .apply()
    }

    private fun doneKey(role: Role, identity: String): String {
        val owner = identity.ifBlank { "device" }
        return "tour_${role.key}_v${TOUR_VERSION}_$owner"
    }

    private fun stepsFor(role: Role): List<TourStep> = when (role) {
        Role.CLIENT -> listOf(
            TourStep(R.string.tour_client_1_title, R.string.tour_client_1_body, R.id.homeFragment, R.id.homeFragment),
            TourStep(R.string.tour_guest_2_title, R.string.tour_guest_2_body, R.id.searchView, R.id.homeFragment),
            TourStep(R.string.tour_explore_services_title, R.string.tour_explore_services_body, R.id.rvCategories, R.id.homeFragment),
            TourStep(R.string.tour_explore_results_title, R.string.tour_explore_results_body, R.id.rvTakers, R.id.homeFragment),
            TourStep(R.string.tour_explore_filters_title, R.string.tour_explore_filters_body, R.id.btnSearchRadius, R.id.homeFragment),
            TourStep(R.string.tour_client_3_title, R.string.tour_client_3_body, R.id.btnCompareMode, R.id.homeFragment),
            TourStep(R.string.tour_client_4_title, R.string.tour_client_4_body, R.id.bookingsFragment, R.id.bookingsFragment),
            TourStep(R.string.tour_client_5_title, R.string.tour_client_5_body, R.id.btnNotifications, R.id.homeFragment),
            TourStep(R.string.tour_client_6_title, R.string.tour_client_6_body, R.id.profileFragment, R.id.profileFragment),
        )
        Role.CREATOR -> listOf(
            TourStep(R.string.tour_client_1_title, R.string.tour_client_1_body, R.id.homeFragment, R.id.homeFragment),
            TourStep(R.string.tour_guest_2_title, R.string.tour_guest_2_body, R.id.searchView, R.id.homeFragment),
            TourStep(R.string.tour_explore_services_title, R.string.tour_explore_services_body, R.id.rvCategories, R.id.homeFragment),
            TourStep(R.string.tour_explore_results_title, R.string.tour_explore_results_body, R.id.rvTakers, R.id.homeFragment),
            TourStep(R.string.tour_creator_1_title, R.string.tour_creator_1_body, R.id.takerDashboardFragment, R.id.takerDashboardFragment),
            TourStep(R.string.tour_creator_2_title, R.string.tour_creator_2_body, R.id.btnMarkAvailable, R.id.takerDashboardFragment),
            TourStep(R.string.tour_creator_3_title, R.string.tour_creator_3_body, R.id.bookingsFragment, R.id.bookingsFragment),
            TourStep(R.string.tour_creator_4_title, R.string.tour_creator_4_body, R.id.takerProfileFragment, R.id.takerProfileFragment),
            TourStep(R.string.tour_creator_5_title, R.string.tour_creator_5_body, R.id.btnAddPost, R.id.takerProfileFragment),
            TourStep(R.string.tour_creator_6_title, R.string.tour_creator_6_body, R.id.cardVerificationPrompt, R.id.takerProfileFragment),
        )
        Role.GUEST -> listOf(
            TourStep(R.string.tour_guest_1_title, R.string.tour_guest_1_body, R.id.homeFragment, R.id.homeFragment),
            TourStep(R.string.tour_guest_2_title, R.string.tour_guest_2_body, R.id.searchView, R.id.homeFragment),
            TourStep(R.string.tour_explore_services_title, R.string.tour_explore_services_body, R.id.rvCategories, R.id.homeFragment),
            TourStep(R.string.tour_explore_results_title, R.string.tour_explore_results_body, R.id.rvTakers, R.id.homeFragment),
            TourStep(R.string.tour_explore_filters_title, R.string.tour_explore_filters_body, R.id.btnSearchRadius, R.id.homeFragment),
            TourStep(R.string.tour_guest_3_title, R.string.tour_guest_3_body, R.id.profileFragment, R.id.profileFragment),
        )
    }
}
