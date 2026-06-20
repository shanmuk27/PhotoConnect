package com.photoconnect.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.photoconnect.utils.toAvailabilityColor
import java.text.SimpleDateFormat
import java.util.*

class AvailabilityCalendarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null, def: Int = 0) : View(ctx, attrs, def) {

    enum class SelectionMode { MULTI, SINGLE }

    private var availMap: Map<String, String> = emptyMap()
    private val selectedMulti = mutableSetOf<String>()
    private var selectedSingle: String? = null

    /** Taker dashboard: multi-day availability editing. Taker detail: pick one bookable day. */
    var selectionMode: SelectionMode = SelectionMode.MULTI

    /** Fired when [SelectionMode.SINGLE] tap is not a bookable (green/Available) day. */
    var onInvalidSelectionAttempt: ((message: String) -> Unit)? = null

    private var month = Calendar.getInstance()
    private var cellW = 0f
    private var cellH = 0f
    private val headerH = 130f
    private val corner = 14f
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 34f }
    private val paintDay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textAlign = Paint.Align.CENTER; textSize = 28f }
    private val paintMonth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = 40f; typeface = Typeface.DEFAULT_BOLD }
    private val paintSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#1A56DB") }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    private val days = listOf("S", "M", "T", "W", "T", "F", "S")

    var onMonthChanged: ((String) -> Unit)? = null

    fun setAvailabilityMap(m: Map<String, String>) {
        availMap = m
        invalidate()
    }

    private fun selectionSet(): Set<String> = when (selectionMode) {
        SelectionMode.MULTI -> selectedMulti
        SelectionMode.SINGLE -> selectedSingle?.let { setOf(it) } ?: emptySet()
    }

    fun getSelectedDates(): List<String> = when (selectionMode) {
        SelectionMode.MULTI -> selectedMulti.toList()
        SelectionMode.SINGLE -> listOfNotNull(selectedSingle)
    }

    /** Book flow: the single chosen date, or null. */
    fun getSelectedBookingDate(): String? = selectedSingle

    fun clearSelection() {
        selectedMulti.clear()
        selectedSingle = null
        invalidate()
    }

    fun nextMonth() {
        month.add(Calendar.MONTH, 1)
        clearSelection()
        invalidate()
        onMonthChanged?.invoke(monthFmt.format(month.time))
    }

    fun prevMonth() {
        month.add(Calendar.MONTH, -1)
        clearSelection()
        invalidate()
        onMonthChanged?.invoke(monthFmt.format(month.time))
    }

    fun getCurrentMonthString(): String = monthFmt.format(month.time)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cellW = w / 7f
        cellH = (h - headerH) / 6f
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        setMeasuredDimension(w, (w * 1.1f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val uiMode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        paintMonth.color = if (isNight) Color.WHITE else Color.BLACK
        paintDay.color = if (isNight) Color.LTGRAY else Color.DKGRAY

        canvas.drawText(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time), width / 2f, 55f, paintMonth)
        days.forEachIndexed { i, d -> canvas.drawText(d, cellW * i + cellW / 2f, headerH - 20f, paintDay) }

        val cal = month.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val first = cal.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val selected = selectionSet()
        for (day in 1..daysInMonth) {
            val slot = day - 1 + first
            val col = slot % 7
            val row = slot / 7
            val l = col * cellW
            val t = headerH + row * cellH
            cal.set(Calendar.DAY_OF_MONTH, day)
            val ds = fmt.format(cal.time)
            val status = availMap[ds]
            val bg = when {
                status != null -> status.toAvailabilityColor()
                isNight -> Color.parseColor("#2D3748")
                else -> Color.parseColor("#F1F5F9")
            }
            paintBg.color = bg
            val rect = RectF(l + 4, t + 4, l + cellW - 4, t + cellH - 4)
            canvas.drawRoundRect(rect, corner, corner, paintBg)
            if (ds in selected) canvas.drawRoundRect(rect, corner, corner, paintSel)
            paintText.color = when {
                status != null -> Color.WHITE
                isNight -> Color.WHITE
                else -> Color.parseColor("#374151")
            }
            canvas.drawText(day.toString(), l + cellW / 2f, t + cellH / 2f + paintText.textSize / 3f, paintText)
        }
    }

    private fun startOfDay(): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val col = (e.x / cellW).toInt()
        val row = ((e.y - headerH) / cellH).toInt()
        if (row < 0) return true
        val cal = month.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val first = cal.get(Calendar.DAY_OF_WEEK) - 1
        val day = row * 7 + col - first + 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        if (day !in 1..daysInMonth) return true

        cal.set(Calendar.DAY_OF_MONTH, day)
        val ds = fmt.format(cal.time)

        if (selectionMode == SelectionMode.SINGLE) {
            val dayStart = cal.clone() as Calendar
            dayStart.set(Calendar.HOUR_OF_DAY, 0)
            dayStart.set(Calendar.MINUTE, 0)
            dayStart.set(Calendar.SECOND, 0)
            dayStart.set(Calendar.MILLISECOND, 0)
            if (dayStart.before(startOfDay())) {
                onInvalidSelectionAttempt?.invoke("Choose today or a future date")
                return true
            }

            val status = availMap[ds]
            if (status != "Available") {
                val msg = when (status) {
                    null -> "No availability for this date — pick a green (available) day"
                    "Booked" -> "This date is already booked"
                    "Not Available" -> "Not available for booking"
                    else -> "Pick an available date (green)"
                }
                onInvalidSelectionAttempt?.invoke(msg)
                return true
            }
            selectedSingle = if (selectedSingle == ds) null else ds
            invalidate()
            return true
        }

        // MULTI: toggle any day in month (taker availability editing)
        if (ds in selectedMulti) selectedMulti.remove(ds) else selectedMulti.add(ds)
        invalidate()
        return true
    }
}
