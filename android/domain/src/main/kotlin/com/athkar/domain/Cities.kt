package com.athkar.domain

import com.athkar.core.prayer.Coordinates

/**
 * Offline fallback for when location permission is refused or no fix is available — which, for an
 * app whose whole point is working without a network, has to be a first-class path rather than an
 * error state. Coordinates are city centres; a few kilometres of error moves prayer times by well
 * under a minute.
 */
object Cities {

    val ALL: List<Place> = listOf(
        place("مكة المكرمة", 21.3891, 39.8579),
        place("المدينة المنورة", 24.4672, 39.6111),
        place("الرياض", 24.7136, 46.6753),
        place("جدة", 21.4858, 39.1925),
        place("الدمام", 26.4207, 50.0888),
        place("أبها", 18.2164, 42.5053),
        place("تبوك", 28.3835, 36.5662),
        place("بريدة", 26.3260, 43.9750),
        place("القاهرة", 30.0444, 31.2357),
        place("الإسكندرية", 31.2001, 29.9187),
        place("الخرطوم", 15.5007, 32.5599),
        place("عمّان", 31.9539, 35.9106),
        place("القدس", 31.7683, 35.2137),
        place("غزة", 31.5017, 34.4668),
        place("بيروت", 33.8938, 35.5018),
        place("دمشق", 33.5138, 36.2765),
        place("بغداد", 33.3152, 44.3661),
        place("الكويت", 29.3759, 47.9774),
        place("الدوحة", 25.2854, 51.5310),
        place("المنامة", 26.2285, 50.5860),
        place("أبوظبي", 24.4539, 54.3773),
        place("دبي", 25.2048, 55.2708),
        place("مسقط", 23.5880, 58.3829),
        place("صنعاء", 15.3694, 44.1910),
        place("الرباط", 34.0209, -6.8416),
        place("الدار البيضاء", 33.5731, -7.5898),
        place("الجزائر", 36.7538, 3.0588),
        place("تونس", 36.8065, 10.1815),
        place("طرابلس", 32.8872, 13.1913),
        place("نواكشوط", 18.0735, -15.9582),
        place("مقديشو", 2.0469, 45.3182),
        place("إسطنبول", 41.0082, 28.9784),
        place("كوالالمبور", 3.1390, 101.6869),
        place("جاكرتا", -6.2088, 106.8456),
        place("إسلام آباد", 33.6844, 73.0479),
        place("لندن", 51.5074, -0.1278),
        place("باريس", 48.8566, 2.3522),
        place("برلين", 52.5200, 13.4050),
        place("نيويورك", 40.7128, -74.0060),
        place("تورونتو", 43.6532, -79.3832),
    )

    /** Makkah — the default until the user picks a place or grants location. */
    val DEFAULT: Place = ALL.first()

    private fun place(name: String, latitude: Double, longitude: Double) =
        Place(Coordinates(latitude, longitude), name, isAutomatic = false)

    /** Case-insensitive contains-match over the Arabic names. */
    fun search(query: String): List<Place> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ALL
        return ALL.filter { it.name.contains(trimmed) }
    }
}
