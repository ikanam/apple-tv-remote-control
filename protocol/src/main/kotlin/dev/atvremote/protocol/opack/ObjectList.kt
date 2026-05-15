package dev.atvremote.protocol.opack

internal class ObjectList {
    private val items = ArrayList<ByteArray>()
    fun indexOf(b: ByteArray): Int = items.indexOfFirst { it.contentEquals(b) }
    fun add(b: ByteArray) { items.add(b) }
}
