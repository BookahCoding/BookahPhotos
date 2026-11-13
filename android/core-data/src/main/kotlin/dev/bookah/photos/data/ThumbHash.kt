package dev.bookah.photos.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ThumbHash decoder (the format is public domain, by Evan Wallace): turns a ~25 byte hash into a tiny image of at most 32 x 32 pixels,
 * which is drawn stretched as the placeholder of a photo cell. Returns null for a hash that is too short to read.
 */
class ThumbImage(val width: Int, val height: Int, /** ARGB_8888, one Int per pixel */ val argb: IntArray)

object ThumbHash {
    fun approximateAspectRatio(hash: ByteArray): Float {
        if (hash.size < 5) return 1f
        val header = hash[3].toInt() and 0xFF
        val hasAlpha = (hash[2].toInt() and 0x80) != 0
        val isLandscape = (hash[4].toInt() and 0x80) != 0
        val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header and 7
        val ly = if (isLandscape) header and 7 else (if (hasAlpha) 5 else 7)
        return lx.toFloat() / ly
    }

    fun decode(hash: ByteArray): ThumbImage? {
        if (hash.size < 5) return null
        fun b(i: Int) = if (i < hash.size) hash[i].toInt() and 0xFF else 0
        val header24 = b(0) or (b(1) shl 8) or (b(2) shl 16)
        val header16 = b(3) or (b(4) shl 8)
        val lDc = (header24 and 63) / 63f
        val pDc = ((header24 shr 6) and 63) / 31.5f - 1
        val qDc = ((header24 shr 12) and 63) / 31.5f - 1
        val lScale = ((header24 shr 18) and 31) / 31f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63) / 63f
        val qScale = ((header16 shr 9) and 63) / 63f
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (b(5) and 15) / 15f else 1f
        val aScale = (b(5) shr 4) / 15f

        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun channel(nx: Int, ny: Int, scale: Float): FloatArray {
            val list = ArrayList<Float>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    val v = (b(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15
                    list.add((v / 7.5f - 1) * scale)
                    acIndex++
                    cx++
                }
            }
            return list.toFloatArray()
        }
        val lAc = channel(lx, ly, lScale)
        val pAc = channel(3, 3, pScale * 1.25f)
        val qAc = channel(3, 3, qScale * 1.25f)
        val aAc = if (hasAlpha) channel(5, 5, aScale) else null

        val ratio = approximateAspectRatio(hash)
        val w = if (ratio > 1) 32 else max(1, (32 * ratio).roundToInt())
        val h = if (ratio > 1) max(1, (32 / ratio).roundToInt()) else 32
        val out = IntArray(w * h)
        val fx = FloatArray(max(lx, if (hasAlpha) 5 else 3))
        val fy = FloatArray(max(ly, if (hasAlpha) 5 else 3))
        for (y in 0 until h) {
            for (cy in fy.indices) fy[cy] = cos(PI / h * (y + 0.5) * cy).toFloat()
            for (x in 0 until w) {
                for (cx in fx.indices) fx[cx] = cos(PI / w * (x + 0.5) * cx).toFloat()
                var l = lDc; var p = pDc; var q = qDc; var a = aDc
                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2
                    while (cx * ly < lx * (ly - cy)) { l += lAc[j] * fx[cx] * fy2; cx++; j++ }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2
                    while (cx < 3 - cy) { val f = fx[cx] * fy2; p += pAc[j] * f; q += qAc[j] * f; cx++; j++ }
                }
                if (aAc != null) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy > 0) 0 else 1
                        val fy2 = fy[cy] * 2
                        while (cx < 5 - cy) { a += aAc[j] * fx[cx] * fy2; cx++; j++ }
                    }
                }
                val bl = l - 2f / 3f * p
                val r = (3f * l - bl + q) / 2f
                val g = r - q
                fun c(v: Float) = max(0, min(255, (255f * min(1f, max(0f, v))).roundToInt()))
                out[y * w + x] = (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(bl)
            }
        }
        return ThumbImage(w, h, out)
    }
}
