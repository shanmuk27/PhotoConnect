package com.photoconnect.utils

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone/email as entered by user: trims, strips leading country code digits to match DB (+91→10 digits). Leaves email unchanged (case/collation preserved). */
fun String.normalizeForLoginIdentity(): String {
    val t = trim()
    if (t.isEmpty()) return t
    if (t.any { ch -> ch == '@' }) return t
    val digits = t.filter(Char::isDigit)
    return if (digits.length >= 10) digits.takeLast(10) else digits
}

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
fun Fragment.toast(msg: String) = requireContext().toast(msg)
fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
}

private val API_DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val DISPLAY_DATE_FMT = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
private val MONTH_FMT = SimpleDateFormat("yyyy-MM", Locale.US)

fun Date.toApiDate() = API_DATE_FMT.format(this)!!
fun Date.toMonthString() = MONTH_FMT.format(this)!!
fun String.toDisplayDate(): String =
    try { DISPLAY_DATE_FMT.format(API_DATE_FMT.parse(this)!!) } catch (e: Exception) { this }
fun currentMonthString() = Date().toMonthString()

val SERVICE_TYPES = listOf(
    "candid_photography",
    "candid_videography",
    "traditional_photography",
    "traditional_videography",
    "drone",
    "led_wall",
    "other",
)
val SERVICE_LABELS = listOf(
    "Candid Photography",
    "Candid Videography",
    "Traditional Photography",
    "Traditional Videography",
    "Drone Shots",
    "LED Wall",
    "Other",
)
val SERVICE_ICONS = listOf("CP", "CV", "TP", "TV", "DR", "LW", "OT")

fun String.toServiceLabel() = when (this) {
    "candid_photography" -> "Candid Photography"
    "candid_videography" -> "Candid Videography"
    "traditional_photography" -> "Traditional Photography"
    "traditional_videography" -> "Traditional Videography"
    "drone" -> "Drone Shots"
    "led_wall" -> "LED Wall"
    else -> this.replace("_", " ").replaceFirstChar { it.uppercase() }
}

fun String.toServiceIcon() = when (this) {
    "candid_photography" -> "CP"
    "candid_videography" -> "CV"
    "traditional_photography" -> "TP"
    "traditional_videography" -> "TV"
    "drone" -> "DR"
    "led_wall" -> "LW"
    else -> "OT"
}

fun String.toServiceTypeOrNull(): String? {
    val index = SERVICE_LABELS.indexOf(this)
    return SERVICE_TYPES.getOrNull(index)
}

fun Collection<String>.toServiceSummary(): String =
    filter { it.isNotBlank() }
        .map { it.toServiceLabel() }
        .distinct()
        .joinToString(", ")
        .ifEmpty { "Services not selected" }

fun Collection<String>.primaryServiceType(): String =
    firstOrNull { it.isNotBlank() }.orEmpty()

fun String.toAvailabilityColor() = when (this) {
    "Available" -> android.graphics.Color.parseColor("#22C55E")
    "Booked" -> android.graphics.Color.parseColor("#EF4444")
    "Not Available" -> android.graphics.Color.parseColor("#94A3B8")
    else -> android.graphics.Color.parseColor("#94A3B8")
}

fun String.isValidPhone() = this.length >= 10 && this.all { it.isDigit() }
fun String.isValidEmail() = android.util.Patterns.EMAIL_ADDRESS.matcher(this).matches()
