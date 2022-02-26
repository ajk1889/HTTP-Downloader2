package resonance.http.httpdownloader.helpers

import resonance.http.httpdownloader.core.str
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

class Generator(
    var limit: Long = Long.MAX_VALUE
) : InputStream() {
    companion object {
        private fun Long.pow(power: Int): Long {
            var ans = 1L
            for (i in 1..power) ans *= this
            return ans
        }

        /**
         * @return Pair of "the index at which number's digit changes; Eg: 9->10, 99->100"
         * and "digits in number just before change" for above Eg. digits = 1, 2
         */
        private fun lowerDigitChangeIndex(index: Long): Pair<Long, Int> {
            var upperIndex = 1L
            var lowerIndex = 1L
            var digits = 0
            while (index < lowerIndex || index > upperIndex) {
                lowerIndex = upperIndex
                upperIndex += (digits + 2) * (10L.pow(digits + 1) - 10L.pow(digits))
                digits += 1
            }
            return lowerIndex to digits
        }

        /**
         * @return Pair of "the number of space characters behind given index"
         * and "number extra characters from last space, if char in given index is not space"
         */
        private fun spacesBehind(index: Long): Pair<Long, Int> {
            val (lowIndex, digits) = lowerDigitChangeIndex(index)
            val countOfLowerDigitNumbers = max(0, 10L.pow(digits - 1) - 1)
            val spaces = countOfLowerDigitNumbers + ((index - lowIndex) / (digits + 1))
            val extraChars = (index - lowIndex) % (digits + 1)
            return spaces to extraChars.toInt()
        }

        /**
         * @return the character at given position
         */
        operator fun get(index: Long): Char {
            if (index == 0L) return '0'
            val (num, extra) = spacesBehind(index)
            return (" " + (num + 1))[extra]
        }

        /**
         * The characters (as String) in given range
         */
        operator fun get(range: Pair<Long, Long>): String {
            val (start, end) = range
            return when (start) {
                end -> ""
                0L -> {
                    val totalSpaces = spacesBehind(end).first + 1
                    val str = buildString(0, totalSpaces + 1)
                    if (str.length < end) str.append(' ').str
                    else str.substring(0, end.toInt())
                }
                else -> {
                    var (startSpaces, startExtraChars) = spacesBehind(start)
                    startSpaces += 1
                    val endSpaces = spacesBehind(end).first + 1
                    val str = buildString(startSpaces, endSpaces + 1).insert(0, ' ')
                        .substring(startExtraChars)
                    when {
                        str.length == (end - start).toInt() -> str
                        str.length < end - start -> "$str "
                        else -> str.substring(0, (end - start).toInt())
                    }
                }
            }
        }

        private fun buildString(start: Long, end: Long): StringBuilder {
            var i = start
            val builder = StringBuilder()
            while (i < end) {
                builder.append(i).append(" ")
                i += 1
            }
            builder.removeSuffix(" ")
            return builder
        }
    }

    var offset: Long = 0
        set(value) {
            seek = seek - field + value
            field = value
        }

    private var seek = offset
    override fun read(): Int {
        if (seek >= limit) return -1
        return Generator[seek++].toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (seek >= limit) return -1
        val gen = Generator[seek to min(seek + len, limit)]
        gen.toByteArray().copyInto(b, off)
        seek += gen.length
        return gen.length
    }

    override fun reset() {
        seek = offset
    }

    override fun skip(n: Long): Long {
        seek += n
        return seek
    }

    val length: Long get() = limit - offset
}