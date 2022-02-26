package resonance.http.httpdownloader.core

import android.net.Uri

interface InputObj {
    fun read(buffer: ByteArray, len: Int): Int
    fun close()
}

interface OutputObj {
    fun write(buffer: ByteArray, len: Int)
    fun length(): Long
    fun close()
    fun reset(): Boolean
    fun read(len: Int): ByteArray
    fun getUri(): Uri
}

val errorCode get() = "RXJyb3IgY29kZQ=="