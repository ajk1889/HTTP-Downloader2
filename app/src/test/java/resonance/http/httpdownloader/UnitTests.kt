package resonance.http.httpdownloader

import org.junit.Assert.assertEquals
import org.junit.Test
import resonance.http.httpdownloader.core.*
import resonance.http.httpdownloader.helpers.Generator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.random.nextInt

class UnitTests {
    @Test
    fun concurrentModificationTest() {
        val map = BlockingMap<Int, Int>()
        val threads = arrayOf(
            thread {
                for (i in 0..1000000) map[i] = i
            }, thread {
                for (i in 0..100000) map.copyToMap()
            }, thread {
                for (i in 0..10000) map.clear()
            }
        )
        for (t in threads) t.join()
    }

    @Test
    fun renameFile_isCorrect() {
        assertEquals(renameDuplicateFile("a-123"), "a-124")
        assertEquals(renameDuplicateFile("a.-123"), "a-1.-123")
        assertEquals(renameDuplicateFile("a-100.-123"), "a-101.-123")
        assertEquals(renameDuplicateFile("a"), "a-1")
        assertEquals(renameDuplicateFile("a-askjd12-123k"), "a-askjd12-123k-1")
        assertEquals(renameDuplicateFile("asd."), "asd-1.")
        assertEquals(renameDuplicateFile("asd.zip"), "asd-1.zip")
        assertEquals(renameDuplicateFile("bag-12.zip"), "bag-13.zip")
        assertEquals(renameDuplicateFile("bag-12-14.zip"), "bag-12-15.zip")
        assertEquals(renameDuplicateFile("bag-12.part14"), "bag-13.part14")
        assertEquals(renameDuplicateFile("bag.asd.-12-12.-14"), "bag.asd.-12-13.-14")
        assertEquals(
            renameDuplicateFile("bag.asd.-12-12.-hello.txt"),
            "bag.asd.-12-12.-hello-1.txt"
        )
        assertEquals(renameDuplicateFile("bag.asd.-12--hello."), "bag.asd.-12--hello-1.")
        assertEquals(renameDuplicateFile("bag-12--hello-12"), "bag-12--hello-13")
        assertEquals(renameDuplicateFile("file-1.mp4.zip"), "file-1.mp4-1.zip")
        assertEquals(renameDuplicateFile("file-1.mp4-.zip"), "file-1.mp4--1.zip")
        assertEquals(renameDuplicateFile("file-1.mp4-12.zip"), "file-1.mp4-13.zip")
        var name = "file.extension"
        repeat(9) { name = renameDuplicateFile(name) }
        assertEquals(name, "file-9.extension")
    }

    @Test
    fun addPartNum_isCorrect() {
        assertEquals(addPartNumTo("a.zip", 1), "a (part1).zip")
        assertEquals(addPartNumTo("a.zip", 134), "a (part134).zip")
        assertEquals(addPartNumTo("a.", 134), "a (part134).")
        assertEquals(addPartNumTo("awe", 23), "awe (part23)")
        assertEquals(addPartNumTo("awe.part1", 23), "awe (part23).part1")
    }

    @Test
    fun arraySlicing() {
        val arr = ByteArray(10) { it.toByte() }
        assert(arr[0 to 5].contentEquals(byteArrayOf(0, 1, 2, 3, 4)))
        assert(arr[5 to 10].contentEquals(byteArrayOf(5, 6, 7, 8, 9)))
        assert(arr[5 to -1].contentEquals(byteArrayOf(5, 6, 7, 8)))
        assert(arr[-5 to -1].contentEquals(byteArrayOf(5, 6, 7, 8)))
    }

    @Test
    fun downloadRangeTest() {
        //normal cases
        assertEquals(getRangeOf(1, 3, 10), 0L to 4L)
        assertEquals(getRangeOf(2, 3, 10), 4L to 7L)
        assertEquals(getRangeOf(3, 3, 10), 7L to 10L)

        //edge cases
        assertEquals(getRangeOf(1, 7, 10), 0L to 4L)
        assertEquals(getRangeOf(3, 7, 10), 5L to 6L)
        assertEquals(getRangeOf(3, 10, 10), 2L to 3L)
        assertEquals(getRangeOf(3, 10, 1), 1L to 1L)
        assertEquals(getRangeOf(1, 10, 1), 0L to 1L)
        assertEquals(getRangeOf(1, 1, 11), 0L to 11L)
    }

    @Test
    fun orElseTest() {
        assertEquals(-1L orElse 100L, 100L)
        assertEquals(100L orElse -1L, 100L)
        assertEquals(100L orElse 100L, 100L)
        assertEquals(10L orElse 100L, 10L)
        assertEquals(100L orElse 10L, 10L)
    }

    @Test
    fun addSuffixTest() {
        assertEquals(addSuffixToFileName("ajk.ask", "-part3"), "ajk-part3.ask")
        assertEquals(addSuffixToFileName("ajkask", "-part3"), "ajkask-part3")
        assertEquals(addSuffixToFileName("ajkask", ".part3"), "ajkask.part3")
        assertEquals(addSuffixToFileName("ajkask", ".part3"), "ajkask.part3")
        assertEquals(addSuffixToFileName("a.j.k.a.s.k", "-part3"), "a.j.k.a.s-part3.k")
    }

    @Test
    fun joinerTest() {
        val original = ByteArrayOutputStream()
        val ipObjects = Array<InputStream>(Random.nextInt(2..50)) {
            val b = Random.nextBytes(Random.nextInt(5.MB))
            original.write(b)
            object : ByteArrayInputStream(b) {
                override fun read(b: ByteArray?, off: Int, len: Int): Int {
                    return super.read(b, off, Random.nextInt(1..len))
                }
            }
        }
        val opObj = ByteArrayOutputStream()

        val joiner = JoinerObject(ipObjects, opObj)
        val time = now()
        joiner.onComplete = {
            log(
                "Done",
                now() - time,
                original.toByteArray().toList() == opObj.toByteArray().toList(),
                formatSize(joiner.reached),
                joiner.currentPart
            )
        }
        joiner.startJoining().join()
    }

    @Test
    fun generatorTest() {
        assertEquals("0 1 2 3 4 5 6 ", Generator[0L to "0 1 2 3 4 5 6 ".length.toLong()])
        assertEquals("3 4 5 6", Generator[6L to "0 1 2 3 4 5 6".length.toLong()])
        assertEquals('0', Generator[0L])
        assertEquals('6', Generator["0 1 2 3 4 5 6".length - 1L])
        val bytes = ByteArray(50 * 1024)
        val gen = Generator()
        println(gen.read(bytes))
        println(gen.read(bytes))
        assertEquals(
            List(500000) { it }.joinToString(" ").substring(50 * 1024, 2 * 50 * 1024),
            String(bytes)
        )
    }

    @Test
    fun silencer() {
        fun returnTest(bool: Boolean): Int? {
            silently {
                if (bool) return 3 // this return statement is intentional to test function inlining
                else throw RuntimeException("")
            }
            return 6
        }
        silently { throw RuntimeException("Nothing") }
        silently { throw OutOfMemoryError("Nothing") }

        // we should get 3, not get 6. The return should break the function
        assertEquals(returnTest(true), 3)
        assertEquals(returnTest(false), 6)
    }

    @Test
    fun otherWiseTest() {
        assertEquals({ "23".toInt() } otherwise 3, 23)
        assertEquals({ "23u".toInt() } otherwise 3, 3)
        assertEquals({ "23u".toInt() } otherwise null, null)
    }
}
