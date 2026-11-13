package dev.bookah.photosspike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Phone spike: loads the shrunk XLM-R text model, runs 38 pre-tokenised queries, compares with the PC, times a 50,000-vector search. */
class MainActivity : Activity() {
    private lateinit var out: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply { text = "Run the test" }
        out = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 64, 16, 16); addView(button); addView(ScrollView(context).apply { addView(out) }) }
        setContentView(col)
        say("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} cores")
        say("Press the button. It takes about a minute.")
        button.setOnClickListener { button.isEnabled = false; thread { try { run() } catch (t: Throwable) { say("ERROR: $t") }; runOnUiThread { button.isEnabled = true } } }
    }

    private fun say(s: String) { log.append(s).append('\n'); runOnUiThread { out.text = log.toString() } }
    private fun pssMb(): Int { val m = Debug.MemoryInfo(); Debug.getMemoryInfo(m); return m.totalPss / 1024 }

    private fun run() {
        log.setLength(0)
        say("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Runtime.getRuntime().availableProcessors()} cores")
        say("Memory (PSS) at start: ${pssMb()} MB")
        val dst = File(filesDir, "text_small.onnx")
        var t = SystemClock.elapsedRealtime()
        if (!dst.exists() || dst.length() < 1000) assets.open("text_small.onnx").use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        say("Model file: ${dst.length() / 1_000_000} MB; copy from the app package: ${SystemClock.elapsedRealtime() - t} ms (first run only)")

        val env = OrtEnvironment.getEnvironment()
        t = SystemClock.elapsedRealtime()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val session = env.createSession(dst.absolutePath, opts)
        say("Model load: ${SystemClock.elapsedRealtime() - t} ms; memory (PSS) after load: ${pssMb()} MB")

        val items = JSONArray(assets.open("spike_data.json").bufferedReader(Charsets.UTF_8).readText())
        val vb = assets.open("image_vecs_f16.bin").readBytes()
        val nImg = vb.size / (512 * 2)
        val sb = ByteBuffer.wrap(vb).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val imgs = FloatArray(nImg * 512) { Float.fromBits(halfToFloatBits(sb.get(it))) }

        fun embed(ids: IntArray): FloatArray {
            OnnxTensor.createTensor(env, IntBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { inp ->
                session.run(mapOf("text" to inp)).use { r -> @Suppress("UNCHECKED_CAST") val v = (r[0].value as Array<FloatArray>)[0].copyOf(); val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); for (k in v.indices) v[k] /= n; return v }
            }
        }
        fun top10(q: FloatArray): List<Int> {
            val s = FloatArray(nImg); for (i in 0 until nImg) { var a = 0f; val o = i * 512; for (k in 0 until 512) a += q[k] * imgs[o + k]; s[i] = a }
            return s.indices.sortedByDescending { s[it] }.take(10)
        }

        val first = items.getJSONObject(0).getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
        t = SystemClock.elapsedRealtime(); embed(first); say("First query (cold): ${SystemClock.elapsedRealtime() - t} ms")
        val times = ArrayList<Long>(); val cos = ArrayList<Double>(); val ov = ArrayList<Int>()
        for (n in 0 until items.length()) {
            val it = items.getJSONObject(n); val ids = it.getJSONArray("ids").let { a -> IntArray(a.length()) { k -> a.getInt(k) } }
            val exp = it.getJSONArray("expect").let { a -> FloatArray(a.length()) { k -> a.getDouble(k).toFloat() } }
            val exp10 = it.getJSONArray("top10").let { a -> (0 until a.length()).map { k -> a.getInt(k) }.toSet() }
            t = SystemClock.elapsedRealtimeNanos(); val v = embed(ids); times.add((SystemClock.elapsedRealtimeNanos() - t) / 1000)
            cos.add(v.indices.sumOf { k -> (v[k] * exp[k]).toDouble() }); ov.add(top10(v).count { x -> x in exp10 })
        }
        times.sort()
        say("Query time over ${times.size} queries: median ${times[times.size / 2] / 1000} ms, slowest ${times.last() / 1000} ms")
        say("Same answer as the PC: vector similarity mean ${"%.4f".format(cos.average())}, min ${"%.4f".format(cos.min())}; top-10 photos identical ${"%.1f".format(ov.average())}/10 on average (real vectors)")

        // Brute-force search over 50,000 vectors (the sample's vectors repeated), float32 in memory.
        val big = FloatArray(50_000 * 512); for (i in 0 until 50_000) System.arraycopy(imgs, (i % nImg) * 512, big, i * 512, 512)
        val q = embed(first); val sc = FloatArray(50_000)
        val runs = ArrayList<Long>()
        repeat(5) {
            t = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until 50_000) { var a = 0f; val o = i * 512; for (k in 0 until 512) a += q[k] * big[o + k]; sc[i] = a }
            val best = sc.indices.sortedByDescending { sc[it] }.take(10)
            runs.add((SystemClock.elapsedRealtimeNanos() - t) / 1_000_000); if (best.isEmpty()) say("")
        }
        say("Search over 50,000 photo vectors (plain Kotlin loop): ${runs.sorted()[2]} ms median of 5")
        say("Memory (PSS) at the end, with 50,000 vectors in float32 (about ${50_000 * 512 * 4 / 1_000_000} MB): ${pssMb()} MB")
        say("Done.")
        session.close()
    }

    /** IEEE half to float bits. */
    private fun halfToFloatBits(h: Short): Int {
        val x = h.toInt() and 0xFFFF; val s = (x and 0x8000) shl 16; val e = (x shr 10) and 0x1F; val m = x and 0x3FF
        return when {
            e == 0 -> if (m == 0) s else { var mm = m; var ee = 113; while (mm and 0x400 == 0) { mm = mm shl 1; ee-- }; s or (ee shl 23) or ((mm and 0x3FF) shl 13) }
            e == 31 -> s or 0x7F800000 or (m shl 13)
            else -> s or ((e + 112) shl 23) or (m shl 13)
        }
    }
}
