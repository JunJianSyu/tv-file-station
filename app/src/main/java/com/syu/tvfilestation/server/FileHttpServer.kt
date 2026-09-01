package com.syu.tvfilestation.server

import android.content.Context
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 内嵌 HTTP 文件服务器。
 *
 * 路由：
 * - GET  /              Web 文件管理器页面（assets）
 * - POST /api/login     配对码登录，成功后写入会话 Cookie
 * - GET  /api/status    服务状态
 * - GET  /api/list      目录列表（?path=相对路径）
 * - POST /api/mkdir     新建目录（JSON: {path}）
 * - POST /api/rename    重命名（JSON: {path, newName}）
 * - POST /api/delete    删除（JSON: {path}）
 * - PUT  /api/upload    上传单个文件（?dir=目标目录&relpath=相对路径，请求体为文件内容）
 *
 * 安全：所有路径经 canonical 化后必须位于根目录内，防止路径穿越。
 */
class FileHttpServer(
    private val context: Context,
    port: Int,
    private val auth: AuthManager
) : NanoHTTPD(port) {

    val root: File = Environment.getExternalStorageDirectory()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return try {
            when {
                uri == "/api/login" -> handleLogin(session)
                !auth.isAuthorized(session.headers["cookie"]) -> unauthorizedPage(uri)
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html; charset=utf-8")
                uri == "/api/status" -> handleStatus()
                uri == "/api/list" -> handleList(session)
                uri == "/api/mkdir" -> handleMkdir(session)
                uri == "/api/rename" -> handleRename(session)
                uri == "/api/delete" -> handleDelete(session)
                uri == "/api/upload" -> handleUpload(session)
                else -> jsonError("not found", Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            jsonError(e.message ?: "internal error", Status.INTERNAL_ERROR)
        }
    }

    // ---------- 鉴权 ----------

    private fun handleLogin(session: IHTTPSession): Response {
        val body = readPostBody(session)
        val code = try {
            JSONObject(body ?: "").optString("code")
        } catch (_: Exception) {
            ""
        }
        if (!auth.verifyCode(code)) {
            return jsonError("配对码错误", Status.FORBIDDEN)
        }
        val resp = jsonResponse(JSONObject().put("ok", true).toString())
        resp.addCookieHeader(auth.cookieValue())
        return resp
    }

    private fun unauthorizedPage(uri: String): Response {
        // API 请求返回 401 JSON，页面请求返回 401（前端会展示登录框）
        return if (uri.startsWith("/api/")) {
            jsonError("unauthorized", Status.UNAUTHORIZED)
        } else {
            serveAsset("web/index.html", "text/html; charset=utf-8")
        }
    }

    // ---------- 状态与列表 ----------

    private fun handleStatus(): Response {
        val json = JSONObject()
            .put("name", "TV 文件中转站")
            .put("root", root.absolutePath)
        return jsonResponse(json.toString())
    }

    private fun handleList(session: IHTTPSession): Response {
        val dir = resolveSafe(session.parms["path"] ?: "")
            ?: return jsonError("非法路径", Status.FORBIDDEN)
        if (!dir.isDirectory) return jsonError("目录不存在", Status.NOT_FOUND)

        val entries = JSONArray()
        dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { f ->
                entries.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("dir", f.isDirectory)
                        .put("size", if (f.isDirectory) 0L else f.length())
                        .put("mtime", f.lastModified())
                )
            }
        val json = JSONObject()
            .put("path", root.relativizeCompat(dir))
            .put("entries", entries)
        return jsonResponse(json.toString())
    }

    // ---------- 目录/文件操作 ----------

    private fun handleMkdir(session: IHTTPSession): Response {
        val body = JSONObject(readPostBody(session) ?: "")
        val target = resolveSafe(body.optString("path"))
            ?: return jsonError("非法路径", Status.FORBIDDEN)
        if (target.exists()) return jsonError("目录已存在", Status.CONFLICT)
        if (!target.mkdirs()) return jsonError("创建失败", Status.INTERNAL_ERROR)
        return jsonResponse(JSONObject().put("ok", true).toString())
    }

    private fun handleRename(session: IHTTPSession): Response {
        val body = JSONObject(readPostBody(session) ?: "")
        val src = resolveSafe(body.optString("path"))
            ?: return jsonError("非法路径", Status.FORBIDDEN)
        val newName = body.optString("newName").trim()
        if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
            return jsonError("名称不合法", Status.BAD_REQUEST)
        }
        if (!src.exists()) return jsonError("目标不存在", Status.NOT_FOUND)
        val dest = File(src.parentFile, newName)
        if (dest.exists()) return jsonError("同名文件已存在", Status.CONFLICT)
        if (!src.renameTo(dest)) return jsonError("重命名失败", Status.INTERNAL_ERROR)
        return jsonResponse(JSONObject().put("ok", true).toString())
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val body = JSONObject(readPostBody(session) ?: "")
        val target = resolveSafe(body.optString("path"))
            ?: return jsonError("非法路径", Status.FORBIDDEN)
        if (!target.exists()) return jsonError("目标不存在", Status.NOT_FOUND)
        if (target == root) return jsonError("不能删除根目录", Status.FORBIDDEN)
        if (!target.deleteRecursively()) return jsonError("删除失败", Status.INTERNAL_ERROR)
        return jsonResponse(JSONObject().put("ok", true).toString())
    }

    // ---------- 上传 ----------

    private fun handleUpload(session: IHTTPSession): Response {
        val dirRel = session.parms["dir"] ?: ""
        val relPath = session.parms["relpath"] ?: ""
        if (relPath.isEmpty()) return jsonError("缺少 relpath", Status.BAD_REQUEST)

        val targetDir = resolveSafe(dirRel) ?: return jsonError("非法目录", Status.FORBIDDEN)
        // relpath 可能包含子目录（文件夹上传场景）
        val destRaw = resolveSafe(joinRel(dirRel, relPath))
            ?: return jsonError("非法路径", Status.FORBIDDEN)

        if (!targetDir.isDirectory) return jsonError("目标目录不存在", Status.NOT_FOUND)
        destRaw.parentFile?.mkdirs()

        val dest = uniqueFile(destRaw)
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val input = session.inputStream
        var written = 0L
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(64 * 1024)
            var remaining = contentLength
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                out.write(buf, 0, n)
                written += n
                remaining -= n
            }
        }
        if (contentLength > 0 && written != contentLength) {
            dest.delete()
            return jsonError("传输不完整", Status.BAD_REQUEST)
        }
        val json = JSONObject()
            .put("ok", true)
            .put("savedAs", root.relativizeCompat(dest))
        return jsonResponse(json.toString())
    }

    // ---------- 工具 ----------

    /** 相对路径解析为绝对文件；越界（路径穿越）返回 null */
    private fun resolveSafe(rel: String): File? {
        return try {
            val cleaned = rel.trim().replace('\\', '/').trim('/')
            val candidate = if (cleaned.isEmpty()) root else File(root, cleaned)
            val canon = candidate.canonicalFile
            val rootCanon = root.canonicalPath
            if (!canon.canonicalPath.startsWith(rootCanon)) null else canon
        } catch (_: Exception) {
            null
        }
    }

    private fun joinRel(dir: String, rel: String): String {
        val d = dir.trim().replace('\\', '/').trim('/')
        val r = rel.trim().replace('\\', '/').trim('/')
        return when {
            d.isEmpty() -> r
            r.isEmpty() -> d
            else -> "$d/$r"
        }
    }

    /** 同名冲突时自动追加 (n) 后缀 */
    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val base = file.name.substringBeforeLast('.', file.name)
        val ext = file.extension
        var i = 1
        while (true) {
            val name = if (ext.isEmpty()) "$base($i)" else "$base($i).$ext"
            val candidate = File(file.parentFile, name)
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun File.relativizeCompat(f: File): String {
        val rootPath = this.canonicalPath
        val target = f.canonicalPath
        return if (target == rootPath) "" else target.removePrefix(rootPath).trimStart('/')
    }

    private fun readPostBody(session: IHTTPSession): String? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"]
    }

    private fun jsonResponse(body: String): Response =
        newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", body)

    private fun jsonError(message: String, status: Status): Response =
        newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            JSONObject().put("error", message).toString()
        )

    private fun serveAsset(path: String, mime: String): Response {
        return try {
            val input = context.assets.open(path)
            newChunkedResponse(Status.OK, mime, input)
        } catch (_: Exception) {
            newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "asset not found")
        }
    }
}
