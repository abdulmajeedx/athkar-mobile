package com.athkar.core.prayer

/**
 * A geographic position in signed decimal degrees. North and east are positive.
 *
 * @property latitude degrees, `-90..90`.
 * @property longitude degrees, `-180..180`.
 */
data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        require(!latitude.isNaN() && latitude in -90.0..90.0) {
            "latitude must be between -90 and 90, was $latitude"
        }
        require(!longitude.isNaN() && longitude in -180.0..180.0) {
            "longitude must be between -180 and 180, was $longitude"
        }
    }
}
