package com.app.pakeplus

import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 在 127.0.0.1 上提供 APK [assets] 根目录的静态文件（仅标准库 + [ServerSocket]），供 WebView 通过 http 加载。
 */
class AssetsStaticServer(
    private val requestedPort: Int,
    private val assets: AssetManager,
) {
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "assets-http").apply { isDaemon = true }
    }

    val listeningPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running) return
        val socket = ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running = true
        thread(name = "assets-http-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                } catch (_: SocketException) {
                    if (!running) break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1)
                )
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    writeError(s, 400, "Bad Request")
                    return
                }
                val method = parts[0]
                val isGet = method.equals("GET", ignoreCase = true)
                val isHead = method.equals("HEAD", ignoreCase = true)
                if (!isGet && !isHead) {
                    writeError(s, 405, "Method Not Allowed")
                    return
                }

                // 部分 WebView（尤其 Chromium 新版本）使用绝对 URI：GET http://127.0.0.1:port/path HTTP/1.1
                var path = pathFromRequestTarget(parts[1])
                path = runCatching {
                    URLDecoder.decode(path, StandardCharsets.UTF_8.name())
                }.getOrDefault(path)

                if (path.contains("..")) {
                    writeError(s, 403, "Forbidden")
                    return
                }

                if (path.isEmpty() || path.endsWith('/')) {
                    path = if (path.endsWith('/')) "${path}index.html" else "index.html"
                }

                val body = try {
                    assets.open(path, AssetManager.ACCESS_STREAMING).use { it.readBytes() }
                } catch (_: Exception) {
                    writeError(s, 404, "Not Found")
                    return
                }

                val mime = guessMime(path)
                val head = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ").append(mime).append("\r\n")
                    append("Content-Length: ").append(body.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                BufferedOutputStream(s.getOutputStream()).use { out ->
                    out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
                    if (isGet) {
                        out.write(body)
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "handleClient", e)
            }
        }
    }

    /**
     * 从请求行的 request-target 得到 assets 内相对路径（不含前导 /）。
     * 支持 origin-form（/path）与 absolute-form（http://host:port/path）。
     */
    private fun pathFromRequestTarget(target: String): String {
        val noQuery = target.substringBefore('?').substringBefore('#')
        val rawPath = when {
            noQuery.startsWith("http://", ignoreCase = true) ||
                noQuery.startsWith("https://", ignoreCase = true) -> {
                val u = Uri.parse(noQuery)
                u.path?.trimStart('/') ?: ""
            }
            else -> noQuery.trimStart('/')
        }
        return rawPath
    }

    private fun writeError(socket: Socket, code: Int, msg: String) {
        val body = msg.toByteArray(StandardCharsets.UTF_8)
        val head =
            "HTTP/1.1 $code $msg\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().use { os ->
                os.write(head.toByteArray(StandardCharsets.UTF_8))
                os.write(body)
            }
        }
    }

    private fun guessMime(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "AssetsStaticServer"
    }
}
