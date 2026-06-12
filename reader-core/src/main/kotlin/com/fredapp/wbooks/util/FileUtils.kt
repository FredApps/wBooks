package com.fredapp.wbooks.util

import java.io.File

/**
 * Return a [File] in [dir] named [name] that doesn't yet exist. If [name] is
 * already taken, appends ` (2)`, ` (3)`, â€¦ until a free slot is found.
 */
fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 2
    while (true) {
        candidate = File(dir, "$base ($i)$ext")
        if (!candidate.exists()) return candidate
        i++
    }
}
