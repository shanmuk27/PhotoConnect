package com.photoconnect.utils

import java.util.Locale

class SearchTrie<T> {
    private data class Node<T>(
        val children: MutableMap<Char, Node<T>> = linkedMapOf(),
        val entries: MutableList<Entry<T>> = mutableListOf(),
    )

    private data class Entry<T>(
        val key: String,
        val payload: T,
        val rank: Int,
    )

    private val root = Node<T>()
    private val keys = linkedSetOf<String>()

    fun put(label: String, payload: T, rank: Int = 0, aliases: Collection<String> = emptyList()) {
        (listOf(label) + aliases)
            .map(::normalize)
            .filter { it.length >= 2 }
            .distinct()
            .forEach { key ->
                val payloadKey = "$key|${payload.hashCode()}"
                if (!keys.add(payloadKey)) return@forEach
                val entry = Entry(key, payload, rank)
                var node = root
                addEntry(node, entry)
                key.forEach { char ->
                    node = node.children.getOrPut(char) { Node() }
                    addEntry(node, entry)
                }
            }
    }

    fun search(prefix: String, limit: Int = 8): List<T> {
        val key = normalize(prefix)
        if (key.length < 2) return emptyList()
        var node = root
        key.forEach { char ->
            node = node.children[char] ?: return emptyList()
        }
        return node.entries
            .sortedWith(compareByDescending<Entry<T>> { it.rank }.thenBy { it.key })
            .distinctBy { it.payload.hashCode() }
            .take(limit)
            .map { it.payload }
    }

    private fun addEntry(node: Node<T>, entry: Entry<T>) {
        node.entries += entry
        if (node.entries.size > MAX_NODE_ENTRIES) {
            node.entries.sortWith(compareByDescending<Entry<T>> { it.rank }.thenBy { it.key })
            while (node.entries.size > MAX_NODE_ENTRIES) {
                node.entries.removeAt(node.entries.lastIndex)
            }
        }
    }

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private companion object {
        const val MAX_NODE_ENTRIES = 16
    }
}
