package com.syu.tvfilestation.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 获取设备在局域网中的 IPv4 地址。
 * 优先有线网卡（eth），其次无线（wlan）。双网卡同时活跃时返回多个。
 */
object NetworkUtil {

    fun getLanAddresses(): List<String> {
        val result = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val priority = when {
                    nif.name.startsWith("eth") -> 0
                    nif.name.startsWith("wlan") -> 1
                    else -> 2
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        result.add(priority to addr.hostAddress.orEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun primaryAddress(): String = getLanAddresses().firstOrNull() ?: "0.0.0.0"
}
