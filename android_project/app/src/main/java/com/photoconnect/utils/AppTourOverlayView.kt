package com.photoconnect.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.photoconnect.R

@SuppressLint("ViewConstructor")
class AppTourOverlayView(
    context: Context,
    private val activity: Activity,
    private val steps: List<AppTourManager.TourStep>,
    private val onComplete: () -> Unit
) : FrameLayout(context) {

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#4D000000") // 30% opacity black
        isAntiAlias = true
    }

    private val spotlightPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private var currentStepIndex = 0
    private var currentTargetView: View? = null
    private var currentTargetRect: RectF? = null
    private var animatedSpotlightRect = RectF()

    private val tooltipView: View
    private val tvTitle: TextView
    private val tvBody: TextView
    private val tvProgress: TextView
    private val btnSkip: View
    private val btnNext: View
    private val tvTapHint: View

    private var spotlightAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var pulseRadius = 0f
    private var finishing = false
    private val density = resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        elevation = 100f // ensure it's on top
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        tooltipView = LayoutInflater.from(context).inflate(R.layout.layout_app_tour_tooltip, this, false)
        tvTitle = tooltipView.findViewById(R.id.tvTourTitle)
        tvBody = tooltipView.findViewById(R.id.tvTourBody)
        tvProgress = tooltipView.findViewById(R.id.tvTourProgress)
        btnSkip = tooltipView.findViewById(R.id.btnTourSkip)
        btnNext = tooltipView.findViewById(R.id.btnTourNext)
        tvTapHint = tooltipView.findViewById(R.id.tvTourTapHint)

        addView(tooltipView)

        btnSkip.setOnClickListener { finishTour() }
        btnNext.setOnClickListener { nextStep() }
    }

    fun start() {
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        requestFocus()
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            finishTour()
            return
        }

        currentStepIndex = index
        val step = steps[index]

        tvTitle.text = context.getString(step.title, index + 1, steps.size)
        tvBody.text = context.getString(step.body)
        tvProgress.text = context.getString(R.string.tour_progress, index + 1, steps.size)
        announceForAccessibility("${tvTitle.text}. ${tvBody.text}")
        
        updateControls(step.actionDriven && step.targetId != null)

        val bottomNavClient = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        val bottomNavTaker = activity.findViewById<BottomNavigationView>(R.id.bottomNavTaker)
        val bottomNav = bottomNavClient ?: bottomNavTaker
        
        // If the step defines a tabId, switch to it
        if (step.tabId != null && bottomNav != null && bottomNav.selectedItemId != step.tabId && bottomNav.menu.findItem(step.tabId) != null) {
            bottomNav.selectedItemId = step.tabId
            // Post delay to let the fragment switch and layout
            postDelayed({ renderStepTarget(step, bottomNav, 0) }, 350)
        } else {
            renderStepTarget(step, bottomNav, 0)
        }
    }

    private fun renderStepTarget(
        step: AppTourManager.TourStep,
        bottomNav: BottomNavigationView?,
        attempt: Int,
    ) {
        currentTargetView = null
        if (step.targetId != null) {
            // Prefer bottom nav item if the targetId matches it
            val navItem = bottomNav?.findViewById<View>(step.targetId)
            currentTargetView = navItem ?: activity.findViewById(step.targetId)
        }

        val target = currentTargetView
        if (target != null && target.isShown && target.width > 0 && target.height > 0 && attempt in 0..4) {
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            postDelayed({ renderStepTarget(step, bottomNav, 5) }, 180L)
            return
        }

        if ((target == null || !target.isShown || target.width == 0 || target.height == 0) && attempt < 4) {
            postDelayed({ renderStepTarget(step, bottomNav, attempt + 1) }, 180L)
            return
        }

        val targetAvailable = target != null && target.isShown && target.width > 0 && target.height > 0
        updateControls(step.actionDriven && targetAvailable)
        if (!targetAvailable) currentTargetView = null

        val newTargetRect = if (target != null && targetAvailable) {
            val targetLoc = IntArray(2)
            target.getLocationInWindow(targetLoc)
            val overlayLoc = IntArray(2)
            this.getLocationInWindow(overlayLoc)

            val x = targetLoc[0] - overlayLoc[0]
            val y = targetLoc[1] - overlayLoc[1]

            // Ensure we got valid coordinates, otherwise center it
            if (target.width > 0 && target.height > 0) {
                val padding = 12 * density
                RectF(
                    x.toFloat() - padding,
                    y.toFloat() - padding,
                    x.toFloat() + target.width + padding,
                    y.toFloat() + target.height + padding
                )
            } else {
                null
            }
        } else {
            null
        }

        val finalTargetRect = newTargetRect ?: run {
            val cx = width / 2f
            val cy = height / 2f
            RectF(cx, cy, cx, cy)
        }

        animateSpotlight(currentTargetRect ?: finalTargetRect, finalTargetRect)
        currentTargetRect = finalTargetRect
        positionTooltip()
    }

    private fun nextStep() {
        showStep(currentStepIndex + 1)
    }

    private fun updateControls(waitForTargetTap: Boolean) {
        btnNext.visibility = if (waitForTargetTap) View.GONE else View.VISIBLE
        tvTapHint.visibility = if (waitForTargetTap) View.VISIBLE else View.GONE
        (btnNext as? TextView)?.text = context.getString(
            if (currentStepIndex == steps.lastIndex) R.string.tour_done else R.string.tour_next
        )
    }

    private fun finishTour() {
        if (finishing) return
        finishing = true
        spotlightAnimator?.cancel()
        pulseAnimator?.cancel()
        animate().alpha(0f).setDuration(300).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onComplete()
        }.start()
    }

    private fun animateSpotlight(from: RectF, to: RectF) {
        spotlightAnimator?.cancel()
        spotlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                animatedSpotlightRect.left = from.left + (to.left - from.left) * fraction
                animatedSpotlightRect.top = from.top + (to.top - from.top) * fraction
                animatedSpotlightRect.right = from.right + (to.right - from.right) * fraction
                animatedSpotlightRect.bottom = from.bottom + (to.bottom - from.bottom) * fraction
                invalidate()
            }
            start()
        }
        
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                pulseRadius = anim.animatedFraction * 32f * density
                invalidate()
            }
            start()
        }
        
        // Also animate tooltip opacity slightly for effect
        tooltipView.alpha = 0.5f
        tooltipView.animate().alpha(1f).setDuration(400).start()
    }

    private fun positionTooltip() {
        // Wait for layout to know sizes
        post {
            val horizontalMargin = (16 * density).toInt()
            val maxTooltipWidth = (360 * density).toInt()
            val params = tooltipView.layoutParams as FrameLayout.LayoutParams
            val wantedWidth = (width - horizontalMargin * 2).coerceAtMost(maxTooltipWidth)
            if (wantedWidth > 0 && params.width != wantedWidth) {
                params.width = wantedWidth
                params.marginStart = horizontalMargin
                params.marginEnd = horizontalMargin
                tooltipView.layoutParams = params
                tooltipView.post { positionTooltip() }
                return@post
            }
            val target = currentTargetRect ?: RectF(width / 2f, height / 2f, width / 2f, height / 2f)
            val tooltipHeight = tooltipView.measuredHeight
            
            // Default to below the spotlight
            var topY = target.bottom + 16 * density
            
            // If it goes off-screen, put it above
            if (topY + tooltipHeight > height) {
                topY = target.top - tooltipHeight - 16 * density
            }
            
            // If it's a centered null target, center it
            if (currentTargetView == null) {
                topY = (height - tooltipHeight) / 2f
            }

            // Ensure tooltip doesn't go above screen
            if (topY < 0) {
                topY = 16 * density
            }

            topY = topY.coerceAtMost((height - tooltipHeight - 16 * density).coerceAtLeast(16 * density))

            tooltipView.animate()
                .y(topY)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Draw spotlight
        val radius = 24 * density
        canvas.drawRoundRect(animatedSpotlightRect, radius, radius, spotlightPaint)
        
        // Draw pulsing effect
        if (pulseRadius > 0f) {
            val pulsePaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                alpha = (255 * (1f - pulseRadius / (32f * density))).toInt().coerceIn(0, 255)
                isAntiAlias = true
            }
            val pulseRect = RectF(
                animatedSpotlightRect.left - pulseRadius,
                animatedSpotlightRect.top - pulseRadius,
                animatedSpotlightRect.right + pulseRadius,
                animatedSpotlightRect.bottom + pulseRadius
            )
            canvas.drawRoundRect(pulseRect, radius + pulseRadius, radius + pulseRadius, pulsePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val step = steps.getOrNull(currentStepIndex)
            if (step?.actionDriven == true && currentTargetView != null && currentTargetRect != null) {
                if (currentTargetRect!!.contains(event.x, event.y)) {
                    currentTargetView?.performClick()
                    nextStep()
                    return true
                }
            }
        }
        // Always consume touch to prevent interaction with background
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishTour()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        spotlightAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
