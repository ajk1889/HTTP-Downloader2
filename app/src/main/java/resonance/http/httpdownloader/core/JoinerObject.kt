package resonance.http.httpdownloader.core

import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Doesn't keep track of total size of files to be joined.
 * So progress can't be obtained from here.
 *
 * It is the duty of caller to handle total size
 */
class JoinerObject(inputs: Array<InputStream>, private val output: OutputStream) {
    companion object {
        const val TYPE = "JoinerObject"
    }

    private val combinedInput = InputsCombiner(inputs)
    val currentPart: Int get() = combinedInput.current

    var onPartComplete: ((index: Int) -> Unit)?
        get() = combinedInput.onPartComplete
        set(value) {
            combinedInput.onPartComplete = value
        }

    private var isRunning = true
    var reached = 0L
    var onComplete: (() -> Unit)? = null
    var onFailed: ((Exception) -> Unit)? = null
    fun startJoining() = thread {
        try {
            val buffer = ByteArray(1.MB)
            var n = combinedInput.read(buffer, buffer.size)
            while (isRunning && n != -1) {
                output.write(buffer, 0, n)
                reached += n
                n = combinedInput.read(buffer, buffer.size)
            }
            if (isRunning) {
                isRunning = false
                onComplete?.invoke()
            }
        } catch (e: Exception) {
            onFailed?.invoke(e)
        }
    }

    private var prevReadBytes = 0L
    private var prevTime = now()
    private var prevSpeed = 0.0
    fun getSpeed(precision: Int = 0): String {
        val currTime = now()
        var speed = if (currTime <= prevTime) 0.0
        else (reached - prevReadBytes) * 1000.0 / (currTime - prevTime)

        if (speed != 0.0) {
            prevReadBytes = reached
            prevTime = currTime
            prevSpeed = speed
        } else speed = prevSpeed

        return "${formatSize(speed, precision, " ")}/s"
    }

    fun stop() {
        isRunning = false
    }
}

class InputsCombiner(private val inputs: Array<InputStream>) : InputObj {
    init {
        if (inputs.isEmpty()) throw RuntimeException("Empty inputs array")
    }
    var current = 0
    var onPartComplete: ((index: Int) -> Unit)? = null

    override fun read(buffer: ByteArray, len: Int): Int {
        val n = inputs[current].read(buffer, 0, len)
        return if (n == -1) {
            onPartComplete?.invoke(current)
            inputs[current].close()

            if (current == inputs.size - 1) -1
            else inputs[++current].read(buffer, 0, len)
        } else n
    }

    override fun close() = inputs.forEach { silently { it.close() } }
}

val retrieve get() = "cmV0cmlldmUg"