package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ biquad; call one of the setters (once per block is plenty) and then [process]. */
internal class Biq {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    private fun norm(c0: Double, c1: Double, c2: Double, d0: Double, d1: Double, d2: Double) {
        b0 = (c0 / d0).toFloat(); b1 = (c1 / d0).toFloat(); b2 = (c2 / d0).toFloat()
        a1 = (d1 / d0).toFloat(); a2 = (d2 / d0).toFloat()
    }

    private fun w(f: Float, sr: Int) = 2 * PI * f.toDouble().coerceIn(10.0, sr * 0.45) / sr

    fun lowPass(f: Float, q: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q)
        norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al); return this
    }
    fun highPass(f: Float, q: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q)
        norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al); return this
    }
    fun bandPass(f: Float, q: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q)
        norm(al, 0.0, -al, 1 + al, -2 * c, 1 - al); return this
    }
    fun peak(f: Float, q: Float, db: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(db / 40.0)
        norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a); return this
    }
    fun lowShelf(f: Float, q: Float, db: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(db / 40.0)
        val s = 2 * sqrt(a) * al
        norm(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
        return this
    }

    fun highShelf(f: Float, q: Float, db: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(db / 40.0)
        val s = 2 * sqrt(a) * al
        norm(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
        return this
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}
