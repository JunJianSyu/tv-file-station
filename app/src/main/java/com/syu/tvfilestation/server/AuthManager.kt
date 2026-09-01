package com.syu.tvfilestation.server

import java.util.UUID
import kotlin.random.Random

/**
 * 配对码与会话令牌管理。
 * 每次服务启动时重新生成配对码与令牌。
 */
class AuthManager {

    @Volatile
    var pairingCode: String = generateCode()
        private set

    @Volatile
    var token: String = UUID.randomUUID().toString()
        private set

    /** 重新生成配对码与令牌（服务启动时调用） */
    fun regenerate() {
        pairingCode = generateCode()
        token = UUID.randomUUID().toString()
    }

    fun verifyCode(code: String): Boolean = code.trim() == pairingCode

    /** 校验请求 Cookie 中是否携带有效令牌 */
    fun isAuthorized(cookieHeader: String?): Boolean {
        if (cookieHeader == null) return false
        return cookieHeader.split(";")
            .map { it.trim() }
            .any { it == "$COOKIE_NAME=$token" }
    }

    fun cookieValue(): String = "$COOKIE_NAME=$token; Path=/; HttpOnly"

    private fun generateCode(): String =
        (0 until CODE_LENGTH).joinToString("") { Random.nextInt(10).toString() }

    companion object {
        const val COOKIE_NAME = "tfs_token"
        private const val CODE_LENGTH = 6
    }
}
