package resonance.http.httpdownloader.core

class BlockingMap<K, V> {
    private val map = LinkedHashMap<K, V>()

    @Synchronized
    fun put(key: K, value: V) = map.put(key, value)

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun remove(key: K) = map.remove(key)

    @Synchronized
    fun isEmpty() = map.isEmpty()

    @Synchronized
    fun copyToMap(): Map<K, V> {
        val copyMap = mutableMapOf<K, V>()
        for ((key, value) in map.entries)
            copyMap[key] = value
        return copyMap
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        put(key, value)
    }
}