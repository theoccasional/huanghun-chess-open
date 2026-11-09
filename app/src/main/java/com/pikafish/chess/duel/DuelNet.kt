package com.pikafish.chess.duel

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 双人对战的传输层：一条 TCP、一行一条 JSON。
 *
 * 只负责"把行送到对面 / 把对面的行交上来"，不做任何棋务判断。
 * 第 5 条：客户端断线后每 2 秒自动重连，重连成功即回调 onConnected，由上层发 sync_request 补谱。
 */
object DuelNet {

    const val TCP_PORT = 45331
    const val UDP_PORT = 45332

    interface Peer {
        /** 收到对面一行 JSON。 */
        fun onLine(line: String)
        /** 对面接入（含重连成功）。上层应在此发 sync_request / 角色与开局信息。 */
        fun onPeerReady()
        /** 对面断开；reason 为原因。 */
        fun onPeerLost(reason: String)
    }

    // ════════════ 房主 ════════════

    class Host(private val code: String, private val nick: String) {
        private val running = AtomicBoolean(true)
        private var server: ServerSocket? = null
        private var beacon: DatagramSocket? = null
        private var socket: Socket? = null
        private var out: BufferedWriter? = null
        private val writeLock = Any()

        fun start(peer: Peer): Int? {
            val ss = try { ServerSocket(TCP_PORT) } catch (_: Exception) { return null }
            server = ss
            thread(isDaemon = true, name = "duel-beacon") { runBeacon() }
            thread(isDaemon = true, name = "duel-accept") {
                while (running.get()) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true, name = "duel-host-conn") { serve(s, peer) }
                }
            }
            return TCP_PORT
        }

        private fun runBeacon() {
            try {
                val ds = DatagramSocket(UDP_PORT).apply { broadcast = true }
                beacon = ds
                val buf = ByteArray(256)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try { ds.receive(p) } catch (_: Exception) { break }
                    val ask = String(p.data, 0, p.length).trim()
                    if (ask != "DUEL?$code") continue
                    val reply = "DUEL!$TCP_PORT".toByteArray()
                    runCatching { ds.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
                }
            } catch (_: Exception) {
            }
        }

        /** 新连接进来即替换旧连接（支持对方杀进程后重进房间）。 */
        private fun serve(s: Socket, peer: Peer) {
            runCatching { socket?.close() }
            socket = s
            s.tcpNoDelay = true
            out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            peer.onPeerReady()
            try {
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.contains("\"ping\"")) { send("{\"type\":\"pong\"}"); continue }
                    if (line.contains("\"pong\"")) continue
                    peer.onLine(line)
                }
            } catch (_: Exception) {
            }
            runCatching { s.close() }
            if (socket === s) { socket = null; out = null }
            if (running.get()) peer.onPeerLost("对手已断开")
        }

        fun send(line: String) {
            synchronized(writeLock) { runCatching { out?.write(line + "\n"); out?.flush() } }
        }

        fun stop() {
            running.set(false)
            runCatching { socket?.close() }
            runCatching { server?.close() }
            runCatching { beacon?.close() }
        }
    }

    // ════════════ 加入者（带自动重连） ════════════

    class Guest(private val code: String, private val nick: String, private val manualIp: String?) {
        private val running = AtomicBoolean(true)
        private var socket: Socket? = null
        private var out: BufferedWriter? = null
        private val writeLock = Any()

        fun start(peer: Peer) {
            thread(isDaemon = true, name = "duel-guest") {
                while (running.get()) {
                    val s = open()
                    if (s == null) {
                        Thread.sleep(1500)
                        continue
                    }
                    socket = s
                    out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                    peer.onPeerReady()
                    try {
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        while (running.get()) {
                            val line = reader.readLine() ?: break
                            if (line.contains("\"ping\"")) { send("{\"type\":\"pong\"}"); continue }
                            if (line.contains("\"pong\"")) continue
                            peer.onLine(line)
                        }
                    } catch (_: Exception) {
                    }
                    runCatching { s.close() }
                    socket = null; out = null
                    if (running.get()) {
                        peer.onPeerLost("连接中断，正在重连…")
                        Thread.sleep(1500)
                    }
                }
            }
            // 第 5 条：每 3 秒一次心跳
            thread(isDaemon = true, name = "duel-ping") {
                while (running.get()) {
                    if (socket != null) send("{\"type\":\"ping\"}")
                    try { Thread.sleep(3000) } catch (_: Exception) { break }
                }
            }
        }

        private fun open(): Socket? {
            val ip = manualIp?.trim()?.takeIf { it.isNotEmpty() } ?: discover()
            if (ip == null) return null
            return try {
                Socket().apply {
                    connect(InetSocketAddress(ip, TCP_PORT), 3000)
                    tcpNoDelay = true
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun discover(): String? {
            val targets = ArrayList<InetAddress>()
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
                }
            }
            runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
            if (targets.isEmpty()) return null
            val ds = try { DatagramSocket() } catch (_: Exception) { return null }
            ds.broadcast = true
            ds.soTimeout = 1200
            try {
                val ask = "DUEL?$code".toByteArray()
                for (t in targets) runCatching { ds.send(DatagramPacket(ask, ask.size, t, UDP_PORT)) }
                val buf = ByteArray(256)
                val p = DatagramPacket(buf, buf.size)
                ds.receive(p)
                val text = String(p.data, 0, p.length).trim()
                // 用"包从哪来"作为房主地址，绝不依赖包里写的 IP
                return if (text.startsWith("DUEL!")) p.address.hostAddress else null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { ds.close() }
            }
        }

        fun send(line: String) {
            synchronized(writeLock) { runCatching { out?.write(line + "\n"); out?.flush() } }
        }

        fun stop() {
            running.set(false)
            runCatching { socket?.close() }
        }
    }

    fun newCode(): String = (100000..999999).random().toString()

    fun localIp(context: Context): String {
        return try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        val h = a.hostAddress ?: continue
                        if (!h.startsWith("169.254")) return h
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
