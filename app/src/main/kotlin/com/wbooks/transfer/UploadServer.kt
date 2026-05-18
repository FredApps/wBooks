package com.wbooks.transfer

import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * Tiny HTTP server bound to the watch's Wi-Fi address. Started/stopped from the
 * "File transfer" settings screen via [UploadServerService] so it survives screen-off.
 *
 * Endpoints (planned):
 *   GET  /            -> minimal HTML listing folders + books, with an upload form
 *   POST /upload      -> multipart upload (one or many .epub/.txt/.fb2/.html files)
 *   POST /mkdir       -> create folder
 *   POST /move        -> sort book into folder
 *   POST /delete      -> remove book or folder
 *
 * Security note: this is unauthenticated on the local network. We'll show the URL
 * + a generated 4-char PIN that the user must enter on the upload page before any
 * mutating endpoint accepts the request.
 */
class UploadServer(
    port: Int,
    private val booksDir: File,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain",
            "wBooks upload server (stub). booksDir=${booksDir.absolutePath}",
        )
    }
}
