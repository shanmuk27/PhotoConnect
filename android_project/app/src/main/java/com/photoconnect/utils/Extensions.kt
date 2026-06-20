package com.photoconnect.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.photoconnect.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone/email as entered by user: trims and strips phone separators. Leaves email unchanged. */
fun String.normalizeForLoginIdentity(): String {
    val t = trim()
    if (t.isEmpty()) return t
    if (t.any { ch -> ch == '@' }) return t
    return t.filter(Char::isDigit)
}

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }
fun View.forceLeftToRightTree() {
    layoutDirection = View.LAYOUT_DIRECTION_LTR
    textDirection = View.TEXT_DIRECTION_LTR
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).forceLeftToRightTree()
        }
    }
}
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
    "wedding_photography",
    "pre_wedding",
    "engagement_photography",
    "birthday_photography",
    "event_photography",
    "corporate_photography",
    "baby_shoot",
    "maternity_shoot",
    "album_design",
    "photo_editing",
    "live_streaming",
    "drone",
    "led_wall",
    "other",
)
val SERVICE_LABELS = listOf(
    "Candid Photography",
    "Candid Videography",
    "Traditional Photography",
    "Traditional Videography",
    "Wedding Photography",
    "Pre-Wedding Shoot",
    "Engagement Photography",
    "Birthday Photography",
    "Event Photography",
    "Corporate Photography",
    "Baby Shoot",
    "Maternity Shoot",
    "Album Design",
    "Photo Editing",
    "Live Streaming",
    "Drone Shots",
    "LED Wall",
    "Other",
)
private val SERVICE_LABEL_RES_IDS = listOf(
    R.string.service_candid_photography,
    R.string.service_candid_videography,
    R.string.service_traditional_photography,
    R.string.service_traditional_videography,
    R.string.service_wedding_photography,
    R.string.service_pre_wedding,
    R.string.service_engagement_photography,
    R.string.service_birthday_photography,
    R.string.service_event_photography,
    R.string.service_corporate_photography,
    R.string.service_baby_shoot,
    R.string.service_maternity_shoot,
    R.string.service_album_design,
    R.string.service_photo_editing,
    R.string.service_live_streaming,
    R.string.service_drone,
    R.string.service_led_wall,
    R.string.service_other,
)
val SERVICE_ICONS = listOf("CP", "CV", "TP", "TV", "WD", "PW", "EG", "BD", "EV", "CO", "BB", "MT", "AL", "ED", "LS", "DR", "LW", "OT")

fun serviceLabels(context: Context) = SERVICE_LABEL_RES_IDS.map { context.getString(it) }

fun String.toServiceLabel(context: Context? = null) = when (this) {
    "candid_photography" -> context?.getString(R.string.service_candid_photography) ?: "Candid Photography"
    "candid_videography" -> context?.getString(R.string.service_candid_videography) ?: "Candid Videography"
    "traditional_photography" -> context?.getString(R.string.service_traditional_photography) ?: "Traditional Photography"
    "traditional_videography" -> context?.getString(R.string.service_traditional_videography) ?: "Traditional Videography"
    "wedding_photography" -> context?.getString(R.string.service_wedding_photography) ?: "Wedding Photography"
    "pre_wedding" -> context?.getString(R.string.service_pre_wedding) ?: "Pre-Wedding Shoot"
    "engagement_photography" -> context?.getString(R.string.service_engagement_photography) ?: "Engagement Photography"
    "birthday_photography" -> context?.getString(R.string.service_birthday_photography) ?: "Birthday Photography"
    "event_photography" -> context?.getString(R.string.service_event_photography) ?: "Event Photography"
    "corporate_photography" -> context?.getString(R.string.service_corporate_photography) ?: "Corporate Photography"
    "baby_shoot" -> context?.getString(R.string.service_baby_shoot) ?: "Baby Shoot"
    "maternity_shoot" -> context?.getString(R.string.service_maternity_shoot) ?: "Maternity Shoot"
    "album_design" -> context?.getString(R.string.service_album_design) ?: "Album Design"
    "photo_editing" -> context?.getString(R.string.service_photo_editing) ?: "Photo Editing"
    "live_streaming" -> context?.getString(R.string.service_live_streaming) ?: "Live Streaming"
    "drone" -> context?.getString(R.string.service_drone) ?: "Drone Shots"
    "led_wall" -> context?.getString(R.string.service_led_wall) ?: "LED Wall"
    "other" -> context?.getString(R.string.service_other) ?: "Other"
    else -> this.replace("_", " ").replaceFirstChar { it.uppercase() }
}

fun String.toServiceIcon() = when (this) {
    "candid_photography" -> "CP"
    "candid_videography" -> "CV"
    "traditional_photography" -> "TP"
    "traditional_videography" -> "TV"
    "wedding_photography" -> "WD"
    "pre_wedding" -> "PW"
    "engagement_photography" -> "EG"
    "birthday_photography" -> "BD"
    "event_photography" -> "EV"
    "corporate_photography" -> "CO"
    "baby_shoot" -> "BB"
    "maternity_shoot" -> "MT"
    "album_design" -> "AL"
    "photo_editing" -> "ED"
    "live_streaming" -> "LS"
    "drone" -> "DR"
    "led_wall" -> "LW"
    else -> "OT"
}

fun String.toServiceTypeOrNull(context: Context? = null): String? {
    val labels = context?.let(::serviceLabels) ?: SERVICE_LABELS
    val index = labels.indexOf(this).takeIf { it >= 0 } ?: SERVICE_LABELS.indexOf(this)
    return SERVICE_TYPES.getOrNull(index)
}

fun String.toServiceTypeInput(context: Context? = null): String? =
    toServiceTypeOrNull(context) ?: toCustomServiceSlug()

fun String.toCustomServiceSlug(): String? {
    val clean = trim()
    if (clean.length < 2) return null
    val slug = clean
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(64)
    return slug.takeIf { it.length >= 2 && it != "other" }
}

fun Collection<String>.toServiceSummary(context: Context? = null): String =
    filter { it.isNotBlank() }
        .map { it.toServiceLabel(context) }
        .distinct()
        .joinToString(", ")
        .ifEmpty { context?.getString(R.string.services_not_selected) ?: "Services not selected" }

fun Collection<String>.primaryServiceType(): String =
    firstOrNull { it.isNotBlank() }.orEmpty()

const val DAY_PART_FULL = "full_day"
const val DAY_PART_FIRST_HALF = "first_half"
const val DAY_PART_SECOND_HALF = "second_half"

val DAY_PARTS = listOf(DAY_PART_FULL, DAY_PART_FIRST_HALF, DAY_PART_SECOND_HALF)

fun String.normalizedDayPart(): String =
    when (this) {
        DAY_PART_FIRST_HALF, "half_day", "morning" -> DAY_PART_FIRST_HALF
        DAY_PART_SECOND_HALF, "evening" -> DAY_PART_SECOND_HALF
        else -> DAY_PART_FULL
    }

fun String.toDayPartLabel(context: Context? = null): String =
    when (normalizedDayPart()) {
        DAY_PART_FIRST_HALF -> context?.getString(R.string.day_part_first_half) ?: "First half"
        DAY_PART_SECOND_HALF -> context?.getString(R.string.day_part_second_half) ?: "Second half"
        else -> context?.getString(R.string.day_part_full) ?: "Full day"
    }

fun String.toDayPartShortLabel(context: Context? = null): String =
    when (normalizedDayPart()) {
        DAY_PART_FIRST_HALF -> context?.getString(R.string.day_part_short_first) ?: "1st half"
        DAY_PART_SECOND_HALF -> context?.getString(R.string.day_part_short_second) ?: "2nd half"
        else -> context?.getString(R.string.day_part_full) ?: "Full day"
    }

fun String.toAvailabilityColor() = when (this) {
    "Available" -> android.graphics.Color.parseColor("#22C55E")
    "Booked" -> android.graphics.Color.parseColor("#EF4444")
    "Not Available" -> android.graphics.Color.parseColor("#94A3B8")
    else -> android.graphics.Color.parseColor("#94A3B8")
}

fun String.isValidPhone() = this.length == 10 && this.all { it.isDigit() }
fun String.isValidEmail() = android.util.Patterns.EMAIL_ADDRESS.matcher(this).matches()

fun Context.isNetworkAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        val nw = cm.activeNetwork ?: return false
        val actNw = cm.getNetworkCapabilities(nw) ?: return false
        return actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || 
               actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) || 
               actNw.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    } else {
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }
}
