package com.photoconnect.utils

fun publicBookingReference(bookingId: Int?): String {
    val id = bookingId ?: 0
    if (id <= 0) return "PC-NEW"
    val mixed = ((id.toLong() * 2_654_435_761L) xor 0x5DEECE66DL) and 0xFFFFFFFFL
    return "PC-" + mixed.toString(36).uppercase().padStart(6, '0').takeLast(6)
}

fun publicEventReference(eventId: Int?): String {
    val id = eventId ?: 0
    if (id <= 0) return "EV-NEW"
    val mixed = ((id.toLong() * 1_597_334_677L) xor 0x9E3779B9L) and 0xFFFFFFFFL
    return "EV-" + mixed.toString(36).uppercase().padStart(6, '0').takeLast(6)
}
