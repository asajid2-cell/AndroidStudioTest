package com.example.moviewebplayer

import android.content.Context
import android.net.Uri
import java.io.BufferedReader

class AdBlocker(context: Context) {
    private val domains: Set<String>

    init {
        val set = mutableSetOf<String>()
        try {
            context.resources.openRawResource(R.raw.adblock_domains).bufferedReader().use(BufferedReader::readLine)?.let { }
            context.resources.openRawResource(R.raw.adblock_domains).bufferedReader().use { br ->
                br.forEachLine { line ->
                    val d = line.trim().lowercase()
                    if (d.isNotEmpty() && !d.startsWith("#")) set.add(d)
                }
            }
        } catch (_: Throwable) {}
        domains = set
    }

    fun shouldBlock(uri: Uri?): Boolean {
        val host = uri?.host?.lowercase() ?: return false
        for (d in domains) {
            if (host == d || host.endsWith(".$d")) return true
        }
        return false
    }
}

