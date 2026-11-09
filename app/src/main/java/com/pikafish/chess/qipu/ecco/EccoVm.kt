package com.pikafish.chess.qipu.ecco

/**
 * EccoVm —— libecco64.so 的 EccoIndex 判定函数的 Kotlin 忠实移植（x86-64 最小 VM）。
 *
 * 与 /data/user/work/ecoo/vm.py 逐句对应，已用 12000 例随机合法谱面与官方库
 * （ctypes 直调 libecco64.so）对拍，0 差异。本文件只负责“跑出 EAX 编号”，
 * 由 EccoIndex 负责把 EAX 转成 3 位 ECCO 编号（如 "C45"）。
 *
 * 指令表与 .so 数据镜像由 gen_kotlin.py 生成于 EccoVmData.kt，勿手改。
 */
internal class EccoVm(input: ByteArray) {

    // ---- 64 位寄存器下标 ----
    private companion object {
        const val RID_RAX = 0
        const val RID_RBX = 1
        const val RID_RCX = 2
        const val RID_RDX = 3
        const val RID_RSI = 4
        const val RID_RDI = 5
        const val RID_RBP = 6
        const val RID_RSP = 7
        const val RID_R8 = 8
        const val RID_R9 = 9
        const val RID_R10 = 10
        const val RID_R11 = 11
        const val RID_R12 = 12
        const val RID_R13 = 13
        const val RID_R14 = 14
        const val RID_R15 = 15

        const val ENTRY = 0xfd20L
        const val LOOP_GUARD = 8_000_000
        const val REP_GUARD = 10_000_000L
        const val MASK64 = -1L

        // 寄存器 id -> (64 位基寄存器下标, 掩码, 位宽)。id 与 EccoVmData.R_* 常量一一对应。
        private val REG_BASE = IntArray(46)
        private val REG_MASK = LongArray(46)
        private val REG_SIZE = IntArray(46)

        private fun regDef(id: Int, base: Int, mask: Long, size: Int) {
            REG_BASE[id] = base
            REG_MASK[id] = mask
            REG_SIZE[id] = size
        }

        init {
            regDef(EccoVmData.R_AL, RID_RAX, 0xffL, 8)
            regDef(EccoVmData.R_AX, RID_RAX, 0xffffL, 16)
            regDef(EccoVmData.R_BH, RID_RBX, 0xff00L, 8)
            regDef(EccoVmData.R_BL, RID_RBX, 0xffL, 8)
            regDef(EccoVmData.R_BPL, RID_RBP, 0xffL, 8)
            regDef(EccoVmData.R_CL, RID_RCX, 0xffL, 8)
            regDef(EccoVmData.R_DIL, RID_RDI, 0xffL, 8)
            regDef(EccoVmData.R_DL, RID_RDX, 0xffL, 8)
            regDef(EccoVmData.R_EAX, RID_RAX, 0xffffffffL, 32)
            regDef(EccoVmData.R_EBP, RID_RBP, 0xffffffffL, 32)
            regDef(EccoVmData.R_EBX, RID_RBX, 0xffffffffL, 32)
            regDef(EccoVmData.R_ECX, RID_RCX, 0xffffffffL, 32)
            regDef(EccoVmData.R_EDI, RID_RDI, 0xffffffffL, 32)
            regDef(EccoVmData.R_EDX, RID_RDX, 0xffffffffL, 32)
            regDef(EccoVmData.R_ESI, RID_RSI, 0xffffffffL, 32)
            regDef(EccoVmData.R_R10B, RID_R10, 0xffL, 8)
            regDef(EccoVmData.R_R10D, RID_R10, 0xffffffffL, 32)
            regDef(EccoVmData.R_R11B, RID_R11, 0xffL, 8)
            regDef(EccoVmData.R_R11D, RID_R11, 0xffffffffL, 32)
            regDef(EccoVmData.R_R12, RID_R12, MASK64, 64)
            regDef(EccoVmData.R_R12B, RID_R12, 0xffL, 8)
            regDef(EccoVmData.R_R12D, RID_R12, 0xffffffffL, 32)
            regDef(EccoVmData.R_R13, RID_R13, MASK64, 64)
            regDef(EccoVmData.R_R13B, RID_R13, 0xffL, 8)
            regDef(EccoVmData.R_R13D, RID_R13, 0xffffffffL, 32)
            regDef(EccoVmData.R_R14, RID_R14, MASK64, 64)
            regDef(EccoVmData.R_R14B, RID_R14, 0xffL, 8)
            regDef(EccoVmData.R_R14D, RID_R14, 0xffffffffL, 32)
            regDef(EccoVmData.R_R15, RID_R15, MASK64, 64)
            regDef(EccoVmData.R_R15B, RID_R15, 0xffL, 8)
            regDef(EccoVmData.R_R15D, RID_R15, 0xffffffffL, 32)
            regDef(EccoVmData.R_R8, RID_R8, MASK64, 64)
            regDef(EccoVmData.R_R8B, RID_R8, 0xffL, 8)
            regDef(EccoVmData.R_R8D, RID_R8, 0xffffffffL, 32)
            regDef(EccoVmData.R_R9B, RID_R9, 0xffL, 8)
            regDef(EccoVmData.R_R9D, RID_R9, 0xffffffffL, 32)
            regDef(EccoVmData.R_RAX, RID_RAX, MASK64, 64)
            regDef(EccoVmData.R_RBP, RID_RBP, MASK64, 64)
            regDef(EccoVmData.R_RBX, RID_RBX, MASK64, 64)
            regDef(EccoVmData.R_RCX, RID_RCX, MASK64, 64)
            regDef(EccoVmData.R_RDI, RID_RDI, MASK64, 64)
            regDef(EccoVmData.R_RDX, RID_RDX, MASK64, 64)
            regDef(EccoVmData.R_RSI, RID_RSI, MASK64, 64)
            regDef(EccoVmData.R_RSP, RID_RSP, MASK64, 64)
            regDef(EccoVmData.R_SI, RID_RSI, 0xffffL, 16)
            regDef(EccoVmData.R_SIL, RID_RSI, 0xffL, 8)
        }
    }

    private val mem = ByteArray(EccoVmData.MEM_SIZE)
    private val r = LongArray(16)

    private var zf = false
    private var sf = false
    private var cf = false
    private var of = false

    init {
        val blob = EccoVmData.DATA_BLOB
        System.arraycopy(blob, 0, mem, 0, blob.size)
        System.arraycopy(input, 0, mem, EccoVmData.INPUT_ADDR, input.size)
        r[RID_RSI] = EccoVmData.INPUT_ADDR.toLong()
        r[RID_RDI] = EccoVmData.INPUT_ADDR.toLong()
        r[RID_RSP] = EccoVmData.STACK_INIT.toLong()
    }

    // ---------------- 寄存器 / 内存 ----------------

    private fun rg(id: Int): Long = r[REG_BASE[id]] and REG_MASK[id]

    private fun writeReg(id: Int, v: Long) {
        val mask = REG_MASK[id]
        val shift = java.lang.Long.numberOfTrailingZeros(mask)
        val base = REG_BASE[id]
        val nv = (v shl shift) and mask
        r[base] = (r[base] and mask.inv()) or nv
    }

    private fun memAddr(s0: Long): Long {
        var addr = (s0 shr 8) and 0xffffffffL
        val baseId = ((s0 shr 40) and 0xffL).toInt() - 1
        val idxId = ((s0 shr 48) and 0xffL).toInt() - 1
        val scale = (s0 shr 56) and 0xffL
        if (baseId >= 0) addr += r[REG_BASE[baseId]]
        if (idxId >= 0) addr += r[REG_BASE[idxId]] * scale
        return addr and 0xffffffffL
    }

    private fun readMem(addr: Long, sz: Int): Long {
        val a = (addr and 0xffffffffL).toInt()
        return when (sz) {
            1 -> mem[a].toLong() and 0xffL
            2 -> {
                (mem[a].toLong() and 0xffL) or ((mem[a + 1].toLong() and 0xffL) shl 8)
            }
            4 -> {
                var v = 0L
                for (i in 0 until 4) v = v or ((mem[a + i].toLong() and 0xffL) shl (8 * i))
                v
            }
            8 -> {
                var v = 0L
                for (i in 0 until 8) v = v or ((mem[a + i].toLong() and 0xffL) shl (8 * i))
                v
            }
            else -> throw IllegalStateException("bad mem size $sz")
        }
    }

    private fun writeMem(addr: Long, v: Long, sz: Int) {
        val a = (addr and 0xffffffffL).toInt()
        when (sz) {
            1 -> mem[a] = (v and 0xffL).toByte()
            2 -> {
                mem[a] = (v and 0xffL).toByte()
                mem[a + 1] = ((v shr 8) and 0xffL).toByte()
            }
            4 -> for (i in 0 until 4) mem[a + i] = ((v shr (8 * i)) and 0xffL).toByte()
            8 -> for (i in 0 until 8) mem[a + i] = ((v shr (8 * i)) and 0xffL).toByte()
            else -> throw IllegalStateException("bad mem size $sz")
        }
    }

    // ---------------- 操作数 ----------------

    /** 计算操作数：0=寄存器, 1=立即数, 2=内存（读取）。 */
    private fun evalOp(s0: Long, s1: Long): Long {
        return when ((s0 and 0xffL).toInt()) {
            0 -> rg(((s0 shr 8) and 0xffL).toInt())
            1 -> s0 shr 8
            2 -> {
                val sz = (s1 and 0xffL).toInt()
                if (sz == 0) throw IllegalStateException("zero-size mem read")
                readMem(memAddr(s0), sz)
            }
            else -> throw IllegalStateException("bad op type ${s0 and 0xffL}")
        }
    }

    private fun storeOp(s0: Long, s1: Long, v: Long) {
        when ((s0 and 0xffL).toInt()) {
            0 -> writeReg(((s0 shr 8) and 0xffL).toInt(), v)
            2 -> writeMem(memAddr(s0), v, (s1 and 0xffL).toInt())
            else -> throw IllegalStateException("bad store op")
        }
    }

    private fun jmpTarget(s0: Long, s1: Long): Long =
        if ((s0 and 0xffL) == 1L) s0 shr 8 else evalOp(s0, s1)

    /** 算术指令位宽：优先寄存器，其次内存，缺省 64。 */
    private fun opSz(base: Int, nops: Int): Int {
        for (i in 0 until nops) {
            val s0 = EccoVmData.INSN[base + 1 + i * 2]
            if ((s0 and 0xffL) == 0L) return REG_SIZE[((s0 shr 8) and 0xffL).toInt()]
        }
        for (i in 0 until nops) {
            val s0 = EccoVmData.INSN[base + 1 + i * 2]
            if ((s0 and 0xffL) == 2L) {
                val sz = (EccoVmData.INSN[base + 2 + i * 2] and 0xffL).toInt()
                if (sz != 0) return sz * 8
            }
        }
        return 64
    }

    /** cmp 位宽：优先寄存器，其次内存，缺省 32。 */
    private fun infSz(base: Int, nops: Int): Int {
        for (i in 0 until nops) {
            val s0 = EccoVmData.INSN[base + 1 + i * 2]
            if ((s0 and 0xffL) == 0L) return REG_SIZE[((s0 shr 8) and 0xffL).toInt()]
        }
        for (i in 0 until nops) {
            val s0 = EccoVmData.INSN[base + 1 + i * 2]
            if ((s0 and 0xffL) == 2L) {
                val sz = (EccoVmData.INSN[base + 2 + i * 2] and 0xffL).toInt()
                if (sz != 0) return sz * 8
            }
        }
        return 32
    }

    /** movzx/movsx 源位宽：寄存器或内存位宽，缺省 32。 */
    private fun srcSize(s0: Long, s1: Long): Int {
        return when ((s0 and 0xffL).toInt()) {
            0 -> REG_SIZE[((s0 shr 8) and 0xffL).toInt()]
            2 -> {
                val sz = (s1 and 0xffL).toInt()
                if (sz != 0) sz * 8 else 32
            }
            else -> 32
        }
    }

    private fun sfz(res: Long, sz: Int): Long {
        val r2 = if (sz >= 64) res else res and ((1L shl sz) - 1)
        zf = r2 == 0L
        sf = ((r2 shr (sz - 1)) and 1L) != 0L
        return r2
    }

    /** 无符号比较 a < b（API 24 无 Long.compareUnsigned，自行实现）。 */
    private fun ult(a: Long, b: Long): Boolean {
        val sa = (a and Long.MIN_VALUE) != 0L
        val sb = (b and Long.MIN_VALUE) != 0L
        return if (sa == sb) a < b else sb
    }

    private fun maskOf(sz: Int): Long = if (sz >= 64) MASK64 else (1L shl sz) - 1

    private fun signBit(sz: Int): Long = 1L shl (sz - 1)

    // ---------------- 指令表 ----------------

    private fun findInsn(addr: Long): Int {
        var lo = 0
        var hi = EccoVmData.N_INSN - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val a = EccoVmData.INSN[mid * 5] and 0xffffffffL
            when {
                a < addr -> lo = mid + 1
                a > addr -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    // ---------------- 执行 ----------------

    fun run(): Int {
        var rip = ENTRY
        var steps = 0
        while (true) {
            if (++steps > LOOP_GUARD) throw IllegalStateException("loop guard")
            val g = findInsn(rip)
            if (g < 0) throw IllegalStateException("no insn @${rip.toString(16)}")
            val base = g * 5
            val hdr = EccoVmData.INSN[base]
            val mn = ((hdr shr 32) and 0xffL).toInt()
            val size = ((hdr shr 40) and 0xffL).toInt()
            val nops = ((hdr shr 56) and 0xffL).toInt()
            val nxt = rip + size

            val s0 = EccoVmData.INSN[base + 1]
            val s1 = EccoVmData.INSN[base + 2]
            val s2 = EccoVmData.INSN[base + 3]
            val s3 = EccoVmData.INSN[base + 4]

            when (mn) {
                EccoVmData.M_NOP -> rip = nxt

                EccoVmData.M_REP_STOSQ -> {
                    var cnt = r[RID_RCX] and MASK64
                    if (cnt > REP_GUARD) throw IllegalStateException("rep count guard")
                    val v = r[RID_RAX]
                    while (cnt > 0) {
                        writeMem(r[RID_RDI], v, 8)
                        r[RID_RDI] = (r[RID_RDI] + 8) and MASK64
                        r[RID_RCX] = (r[RID_RCX] - 1) and MASK64
                        cnt--
                    }
                    rip = nxt
                }

                EccoVmData.M_MOV, EccoVmData.M_MOVZX, EccoVmData.M_MOVSX, EccoVmData.M_MOVSXD -> {
                    var v = evalOp(s2, s3)
                    when (mn) {
                        EccoVmData.M_MOVZX -> {
                            val sz = srcSize(s2, s3)
                            if (sz < 64) v = v and ((1L shl sz) - 1)
                        }
                        EccoVmData.M_MOVSX -> {
                            val sz = srcSize(s2, s3)
                            if (sz < 64 && ((v and (1L shl (sz - 1))) != 0L)) v = v or (-1L shl sz)
                        }
                        EccoVmData.M_MOVSXD -> {
                            v = v and 0xffffffffL
                            // 符号扩展：bit31=1 时高 32 位全置 1（等价于 or 0xffffffff00000000）
                            if ((v and 0x80000000L) != 0L) v = v or -0x100000000L
                        }
                    }
                    storeOp(s0, s1, v)
                    rip = nxt
                }

                EccoVmData.M_LEA -> {
                    storeOp(s0, s1, memAddr(s2))
                    rip = nxt
                }

                EccoVmData.M_CMP -> {
                    val b = evalOp(s2, s3)
                    val a = evalOp(s0, s1)
                    val sz = infSz(base, nops)
                    val m = maskOf(sz)
                    val a0 = a and m
                    val b0 = b and m
                    val rr = a0 - b0
                    zf = rr == 0L
                    sf = ((rr shr (sz - 1)) and 1L) != 0L
                    cf = if (sz >= 64) ult(a0, b0) else a0 < b0
                    of = (((a0 xor b0) and signBit(sz)) != 0L) && (((a0 xor rr) and signBit(sz)) != 0L)
                    rip = nxt
                }

                EccoVmData.M_TEST -> {
                    val b = evalOp(s2, s3)
                    val a = evalOp(s0, s1)
                    val sz = opSz(base, nops)
                    sfz(a and b, sz)
                    cf = false
                    of = false
                    rip = nxt
                }

                EccoVmData.M_ADD -> {
                    val b = evalOp(s2, s3)
                    val a = evalOp(s0, s1)
                    val sz = opSz(base, nops)
                    val m = maskOf(sz)
                    val rr = a + b
                    storeOp(s0, s1, rr)
                    val r2 = rr and m
                    zf = r2 == 0L
                    sf = ((r2 shr (sz - 1)) and 1L) != 0L
                    cf = if (sz >= 64) ult(r2, a and m) else ((rr shr sz) and 1L) != 0L
                    val va = a and m
                    val vb = b and m
                    of = ((va and signBit(sz)) == (vb and signBit(sz))) && ((va xor r2) and signBit(sz)) != 0L
                    rip = nxt
                }

                EccoVmData.M_SUB -> {
                    val b = evalOp(s2, s3)
                    val a = evalOp(s0, s1)
                    val sz = opSz(base, nops)
                    val m = maskOf(sz)
                    val rr = a - b
                    storeOp(s0, s1, rr)
                    val r2 = rr and m
                    zf = r2 == 0L
                    sf = ((r2 shr (sz - 1)) and 1L) != 0L
                    cf = if (sz >= 64) ult(a and m, b and m) else ((rr shr sz) and 1L) != 0L
                    val va = a and m
                    val vb = b and m
                    of = ((va and signBit(sz)) != (vb and signBit(sz))) && ((va xor r2) and signBit(sz)) != 0L
                    rip = nxt
                }

                EccoVmData.M_XOR -> {
                    val b = evalOp(s2, s3)
                    val rr = evalOp(s0, s1) xor b
                    storeOp(s0, s1, rr)
                    sfz(rr, opSz(base, nops))
                    cf = false
                    of = false
                    rip = nxt
                }

                EccoVmData.M_AND -> {
                    val b = evalOp(s2, s3)
                    val rr = evalOp(s0, s1) and b
                    storeOp(s0, s1, rr)
                    sfz(rr, opSz(base, nops))
                    cf = false
                    of = false
                    rip = nxt
                }

                EccoVmData.M_OR -> {
                    val b = evalOp(s2, s3)
                    val rr = evalOp(s0, s1) or b
                    storeOp(s0, s1, rr)
                    sfz(rr, opSz(base, nops))
                    cf = false
                    of = false
                    rip = nxt
                }

                EccoVmData.M_SHL -> {
                    val cnt = evalOp(s2, s3) and 0x3fL
                    val sz = opSz(base, nops)
                    val v = evalOp(s0, s1) and maskOf(sz)
                    val res: Long
                    val carry: Boolean
                    if (cnt >= sz) {
                        res = 0
                        carry = cnt == sz.toLong() && ((v and signBit(sz)) != 0L)
                    } else {
                        res = (v shl cnt.toInt()) and maskOf(sz)
                        val d = sz - cnt.toInt()
                        carry = if (d >= 64) false else ((v shr d) and 1L) != 0L
                    }
                    storeOp(s0, s1, res)
                    sfz(res, sz)
                    cf = carry
                    of = false
                    rip = nxt
                }

                EccoVmData.M_SHR -> {
                    val cnt = evalOp(s2, s3) and 0x3fL
                    val sz = opSz(base, nops)
                    val v = evalOp(s0, s1) and maskOf(sz)
                    val res: Long
                    val carry: Boolean
                    if (cnt >= sz) {
                        res = 0
                        carry = cnt == sz.toLong() && ((v and signBit(sz)) != 0L)
                    } else {
                        res = v ushr cnt.toInt()
                        carry = if (cnt != 0L) ((v shr (cnt.toInt() - 1)) and 1L) != 0L else false
                    }
                    storeOp(s0, s1, res)
                    sfz(res, sz)
                    cf = carry
                    of = false
                    rip = nxt
                }

                EccoVmData.M_SAR -> {
                    val cnt = evalOp(s2, s3) and 0x3fL
                    val sz = opSz(base, nops)
                    val v = evalOp(s0, s1) and maskOf(sz)
                    val sign = (v ushr (sz - 1)) and 1L
                    val res: Long
                    val carry: Boolean
                    if (cnt >= sz) {
                        res = if (sign != 0L) maskOf(sz) else 0
                        carry = cnt == sz.toLong() && ((v and signBit(sz)) != 0L)
                    } else {
                        val cnti = cnt.toInt()
                        // 与 Python 一致：~((1<<(sz-cnt))-1) & ((1<<sz)-1)，即 sz 位内的高 cnt 位
                        // Kotlin 写法 mask xor ((1<<(sz-cnt))-1)：低 (sz-cnt) 位取反即高 cnt 位为 1
                        val highBits = if (sign != 0L && cnti != 0) {
                            maskOf(sz) xor ((1L shl (sz - cnti)) - 1)
                        } else 0L
                        res = (v shr cnti) or highBits
                        carry = if (cnti != 0) ((v shr (cnti - 1)) and 1L) != 0L else false
                    }
                    storeOp(s0, s1, res)
                    sfz(res, sz)
                    cf = carry
                    of = false
                    rip = nxt
                }

                EccoVmData.M_JE -> rip = if (zf) jmpTarget(s0, s1) else nxt
                EccoVmData.M_JNE -> rip = if (!zf) jmpTarget(s0, s1) else nxt
                EccoVmData.M_JLE -> rip = if (zf || sf != of) jmpTarget(s0, s1) else nxt
                EccoVmData.M_JG -> rip = if (!zf && sf == of) jmpTarget(s0, s1) else nxt
                EccoVmData.M_JA -> rip = if (!cf && !zf) jmpTarget(s0, s1) else nxt
                EccoVmData.M_JBE -> rip = if (cf || zf) jmpTarget(s0, s1) else nxt

                EccoVmData.M_JMP -> rip = jmpTarget(s0, s1)

                EccoVmData.M_CALL -> {
                    val tgt = jmpTarget(s0, s1)
                    if (tgt == EccoVmData.STRNCPY.toLong()) {
                        // strncpy(dest=rdi, src=rsi, n=edx)：拷到 NUL 即停，其余补零
                        val dest = r[RID_RDI]
                        val src = r[RID_RSI]
                        val n = r[RID_RDX] and 0xffffffffL
                        var i = 0L
                        while (i < n) {
                            val s = mem[((src + i) and 0xffffffffL).toInt()].toInt() and 0xff
                            mem[((dest + i) and 0xffffffffL).toInt()] = s.toByte()
                            if (s == 0) break
                            i++
                        }
                        var j = i + 1
                        while (j < n) {
                            mem[((dest + j) and 0xffffffffL).toInt()] = 0
                            j++
                        }
                        r[RID_RAX] = dest
                        rip = nxt
                    } else {
                        r[RID_RSP] = (r[RID_RSP] - 8) and MASK64
                        writeMem(r[RID_RSP], nxt, 8)
                        rip = tgt
                    }
                }

                EccoVmData.M_RET, EccoVmData.M_REPZ_RET -> {
                    // `rep ret`(F3 C3) 与 `ret` 语义完全相同（REP 前缀在 RET 上被忽略），
                    // 是编译器常用的短返回写法；此处必须按 RET 处理，否则会掉进调用者后随的
                    // 指令（如跳转表尾部）造成死循环。
                    val ra = readMem(r[RID_RSP], 8)
                    r[RID_RSP] = (r[RID_RSP] + 8) and MASK64
                    if (ra == 0L) return (r[RID_RAX] and 0xffffffffL).toInt()
                    rip = ra
                }

                EccoVmData.M_PUSH -> {
                    val v = evalOp(s0, s1)
                    r[RID_RSP] = (r[RID_RSP] - 8) and MASK64
                    writeMem(r[RID_RSP], v, 8)
                    rip = nxt
                }

                EccoVmData.M_POP -> {
                    val v = readMem(r[RID_RSP], 8)
                    r[RID_RSP] = (r[RID_RSP] + 8) and MASK64
                    storeOp(s0, s1, v)
                    rip = nxt
                }

                EccoVmData.M_CMOVE -> {
                    if (zf) storeOp(s0, s1, evalOp(s2, s3))
                    rip = nxt
                }

                EccoVmData.M_CMOVNE -> {
                    if (!zf) storeOp(s0, s1, evalOp(s2, s3))
                    rip = nxt
                }

                EccoVmData.M_IMUL -> {
                    val a = evalOp(s0, s1)
                    val b = evalOp(s2, s3)
                    val sz = opSz(base, nops)
                    val m = maskOf(sz)
                    val full = a * b
                    val r2 = full and m
                    storeOp(s0, s1, r2)
                    zf = r2 == 0L
                    sf = ((r2 shr (sz - 1)) and 1L) != 0L
                    if (sz < 64) {
                        val signExt = if ((r2 and signBit(sz)) != 0L) full or (-1L shl sz) else r2
                        cf = full != signExt
                        of = cf
                    } else {
                        cf = false
                        of = false
                    }
                    rip = nxt
                }

                else -> throw IllegalStateException("unhandled mnemonic $mn @${rip.toString(16)}")
            }
        }
    }

    /** 便捷入口：对 C2.5 串求 ECCO 编号（EAX，3 字节小端字母），与 libecco64.so EccoIndex 一致。 */
    fun eccoIndex(): Int = run()
}
