package com.pikafish.chess.model

import android.util.Log

/**
 * 亚洲象棋联合会棋例 — 循环着法判定
 *
 * 核心规则（来自《象棋比赛规例》亚洲象棋联合会裁判组制订）：
 *
 * 【棋例总纲】
 * 1. 长照作负 — 无论任何情况，连续不停照将者必须变着，不变作负
 * 2. 长杀、一照一杀、一照一停、一照一要抽吃、一捉一要抽吃 → 双方不变作和
 *    一照一捉（对方全闲）按"长打"处理 → 攻击方必须变着
 * 3. 一子长捉一子作负 — 连续捉同一子者必须变着（兵卒、将帅例外可长捉）
 * 4. 一子分捉两子或多子作和
 * 5. 长拦、长献、长兑、长要抽吃 → 均作和局（守方可以坚持）
 * 6. 双方犯同一禁例作和
 * 7. 一方长照一方长捉 → 长照者必须变着
 * 8. 一方犯例另一方不犯例 → 犯例方变着
 *
 * 【判定流程】
 * 1. 检测同一局面（位置+走棋方）重复出现3次
 * 2. 提取循环中的着法
 * 3. 分类每步着法（照/捉/闲）
 * 4. 根据棋例总纲判定谁须变着或和棋
 * 5. 首次循环 → 警告变着；警告后仍重复 → 判负
 *
 * 【着法分类说明】
 * - 照(CHECK)：走子直接攻击对方将/帅
 * - 捉(CHASE)：走子攻击对方将/帅以外的棋子，产生新的吃子威胁
 * - 闲(IDLE)：不属于照和捉的着法（包括拦、献、兑、杀、闲等守方着法）
 *   其中长拦、长献、长兑均作和，故统一归为"闲"处理
 */
class RepetitionRule {
    companion object {
        private const val TAG = "RepetitionRule"
    }

    /**
     * 循环着法判定结果
     */
    sealed class Verdict {
        /** 没有循环 */
        object NoRepetition : Verdict()
        /** 必须变着（首次检测到违规循环） */
        data class MustChange(val violator: PieceColor, val reason: String) : Verdict()
        /** 判负（违规方在警告后仍重复） */
        data class ForcedLoss(val loser: PieceColor, val reason: String) : Verdict()
        /** 判和 */
        data class ForcedDraw(val reason: String) : Verdict()
    }

    /**
     * 着法分类（简化版）
     * - CHECK: 照将（攻击对方将/帅）→ 攻击方着法，长照作负
     * - CHASE: 捉子（攻击对方将/帅以外的棋子）→ 攻击方着法，长捉作负
     * - IDLE: 闲着（包括拦、献、兑、杀、闲等）→ 守方着法，可坚持作和
     */
    enum class MoveCategory(val chineseName: String) {
        CHECK("照将"),
        CHASE("捉子"),
        IDLE("闲着")
    }

    /**
     * 提取FEN的位置键（位置+走棋方），忽略走棋计数器
     * 同一局面 = 相同棋子排列 + 相同走棋方
     */
    fun positionKey(fen: String): String {
        val parts = fen.split(' ')
        return if (parts.size >= 2) "${parts[0]} ${parts[1]}" else parts[0]
    }

    /**
     * 检查位置历史中的循环着法
     *
     * @param snapshots 棋盘快照列表（FEN），snapshots[0]=初始位置，snapshots[i]=第i步后
     * @param movesUci 走法历史（UCI格式），movesUci[i]对应从snapshots[i]到snapshots[i+1]的走法
     * @param warnedViolator 已被警告必须变着的一方（null=未警告）
     * @return 判定结果
     */
    fun checkRepetition(
        snapshots: List<String>,
        movesUci: List<String>,
        warnedViolator: PieceColor? = null
    ): Verdict {
        // 开局阶段不检查循环着法：至少需要20步（20个快照=19个半回合）
        // 避免开局几步就判和棋的荒谬情况
        if (snapshots.size < 20) return Verdict.NoRepetition

        val currentKey = positionKey(snapshots.last())

        // 查找当前局面在历史中出现的所有位置
        val occurrences = mutableListOf<Int>()
        for (i in snapshots.indices) {
            if (positionKey(snapshots[i]) == currentKey) {
                occurrences.add(i)
            }
        }

        // 需要至少3次出现同一局面才算循环归原
        if (occurrences.size < 3) return Verdict.NoRepetition

        // 取最后一次循环：从倒数第二次出现到当前（最后一次）
        val cycleStart = occurrences[occurrences.size - 2]
        val cycleEnd = occurrences.last()

        Log.d(TAG, "循环检测: 位置出现${occurrences.size}次, 循环范围=$cycleStart..$cycleEnd, " +
                "已警告方=${warnedViolator?.name}")

        // 提取循环中的着法并分类
        val redCategories = mutableListOf<MoveCategory>()
        val blackCategories = mutableListOf<MoveCategory>()
        // 追踪每方捉子的目标（用于区分"长捉一子"和"分捉两子"）
        val redChaseTargets = mutableSetOf<String>()  // 捉了哪些目标（用位置+类型标识）
        val blackChaseTargets = mutableSetOf<String>()
        // 追踪每方捉子的来源（用于区分"一子捉"和"多子捉"）
        val redChaseSources = mutableSetOf<String>()
        val blackChaseSources = mutableSetOf<String>()

        for (i in cycleStart until cycleEnd) {
            if (i >= movesUci.size) break

            val fenBefore = snapshots[i]
            val fenAfter = snapshots[i + 1]
            val uci = movesUci[i]

            val boardBefore = Board()
            boardBefore.loadFromFen(fenBefore)

            val move = Move.fromUci(uci, boardBefore) ?: continue
            val moverColor = move.piece.color

            val boardAfter = Board()
            boardAfter.loadFromFen(fenAfter)

            val category = classifyMove(boardBefore, move, boardAfter)

            Log.d(TAG, "  着法 ${move.toUci()} (${moverColor.name}): ${category.chineseName}")

            if (moverColor == PieceColor.RED) {
                redCategories.add(category)
                if (category == MoveCategory.CHASE) {
                    // 记录捉子目标和来源
                    val targets = getChaseTargets(boardBefore, move, boardAfter)
                    redChaseTargets.addAll(targets.map { it.toUci() })
                    redChaseSources.add(move.from.toUci())
                }
            } else {
                blackCategories.add(category)
                if (category == MoveCategory.CHASE) {
                    val targets = getChaseTargets(boardBefore, move, boardAfter)
                    blackChaseTargets.addAll(targets.map { it.toUci() })
                    blackChaseSources.add(move.from.toUci())
                }
            }
        }

        if (redCategories.isEmpty() && blackCategories.isEmpty()) {
            return Verdict.NoRepetition
        }

        // 分析每方的着法模式
        val redAllCheck = redCategories.isNotEmpty() && redCategories.all { it == MoveCategory.CHECK }
        val blackAllCheck = blackCategories.isNotEmpty() && blackCategories.all { it == MoveCategory.CHECK }
        val redAllChase = redCategories.isNotEmpty() && redCategories.all { it == MoveCategory.CHASE }
        val blackAllChase = blackCategories.isNotEmpty() && blackCategories.all { it == MoveCategory.CHASE }

        Log.d(TAG, "红方: allCheck=$redAllCheck, allChase=$redAllChase, " +
                "categories=${redCategories.map { it.name }}")
        Log.d(TAG, "黑方: allCheck=$blackAllCheck, allChase=$blackAllChase, " +
                "categories=${blackCategories.map { it.name }}")

        // ═══════════════════════════════════════════════════════════════
        // 应用棋例总纲
        // ═══════════════════════════════════════════════════════════════

        // ── 规则1：长照作负（最高优先级）──
        if (redAllCheck && blackAllCheck) {
            // 双方循环长照（解照反照）→ 作和
            return Verdict.ForcedDraw("双方循环长照（解照反照），判和局")
        }
        if (redAllCheck) {
            // 红方长照 → 红方必须变着
            return if (warnedViolator == PieceColor.RED) {
                Verdict.ForcedLoss(PieceColor.RED, "红方长照不变，判负")
            } else {
                Verdict.MustChange(PieceColor.RED, "红方长照")
            }
        }
        if (blackAllCheck) {
            // 黑方长照 → 黑方必须变着
            return if (warnedViolator == PieceColor.BLACK) {
                Verdict.ForcedLoss(PieceColor.BLACK, "黑方长照不变，判负")
            } else {
                Verdict.MustChange(PieceColor.BLACK, "黑方长照")
            }
        }

        // ── 规则7：一方长照一方长捉 → 长照者变着 ──
        // （长照已在上面处理，此处处理长捉方不犯例的情况）

        // ── 规则3：长捉作负 ──
        // 但注意：一子分捉两子或多子作和（不是作负）
        if (redAllChase && blackAllChase) {
            // 双方长捉 → 作和
            return Verdict.ForcedDraw("双方长捉，判和局")
        }
        if (redAllChase) {
            // 红方长捉 → 检查是否为"分捉两子"（作和）还是"长捉一子"（作负）
            if (redChaseSources.size == 1 && redChaseTargets.size >= 2) {
                // 一子分捉两子以上 → 作和
                return Verdict.ForcedDraw("红方一子分捉多子，判和局")
            }
            // 长捉一子或多子合捉 → 作负
            return if (warnedViolator == PieceColor.RED) {
                Verdict.ForcedLoss(PieceColor.RED, "红方长捉不变，判负")
            } else {
                Verdict.MustChange(PieceColor.RED, "红方长捉")
            }
        }
        if (blackAllChase) {
            // 黑方长捉 → 检查是否为"分捉两子"（作和）还是"长捉一子"（作负）
            if (blackChaseSources.size == 1 && blackChaseTargets.size >= 2) {
                // 一子分捉两子以上 → 作和
                return Verdict.ForcedDraw("黑方一子分捉多子，判和局")
            }
            // 长捉一子或多子合捉 → 作负
            return if (warnedViolator == PieceColor.BLACK) {
                Verdict.ForcedLoss(PieceColor.BLACK, "黑方长捉不变，判负")
            } else {
                Verdict.MustChange(PieceColor.BLACK, "黑方长捉")
            }
        }

        // ── 其他情况 ──
        // 长杀、长拦、长献、长兑 → 作和（守方可以坚持）
        // 一照一杀、一照一捉、一照一闲等混合模式 → 作和
        // 双方均无犯例 → 作和
        //
        // 特殊判断：如果一方有照也有着（非全照），另一方全闲 →
        // 一照一闲作和，不需要变着
        //
        // 如果一方有捉也有闲（非全捉），另一方全闲 →
        // 不算长捉（因为不是每步都捉），作和

        val redHasAttack = redCategories.any { it == MoveCategory.CHECK || it == MoveCategory.CHASE }
        val blackHasAttack = blackCategories.any { it == MoveCategory.CHECK || it == MoveCategory.CHASE }
        val redAllAttack = redCategories.isNotEmpty() &&
                redCategories.all { it == MoveCategory.CHECK || it == MoveCategory.CHASE }
        val blackAllAttack = blackCategories.isNotEmpty() &&
                blackCategories.all { it == MoveCategory.CHECK || it == MoveCategory.CHASE }

        // 一方全攻击（照+捉混合），另一方全闲 → 攻击方须变着
        // 因为混合照捉属于"长打"，如果对方全闲，则攻击方犯例
        if (redAllAttack && !blackHasAttack) {
            return if (warnedViolator == PieceColor.RED) {
                Verdict.ForcedLoss(PieceColor.RED, "红方长打不变，判负")
            } else {
                Verdict.MustChange(PieceColor.RED, "红方长打（照捉混合）")
            }
        }
        if (blackAllAttack && !redHasAttack) {
            return if (warnedViolator == PieceColor.BLACK) {
                Verdict.ForcedLoss(PieceColor.BLACK, "黑方长打不变，判负")
            } else {
                Verdict.MustChange(PieceColor.BLACK, "黑方长打（照捉混合）")
            }
        }

        // 双方都有攻击，或双方都没有攻击 → 不自动判和
        // 只有明确的规则性判和（双方长照、双方长捉、一子分捉多子）才在上文判和
        // 其余情况（长拦、长献、长兑等）需要更高次数的重复才判和
        val totalCycles = occurrences.size - 1  // 完整循环次数
        if (totalCycles >= 4) {
            // 4次以上重复才考虑判和（长拦、长献等守方着法）
            return Verdict.ForcedDraw("循环着法（${totalCycles}次重复），双方不变作和")
        }
        // 重复次数不足，不判和
        return Verdict.NoRepetition
    }

    /**
     * 分类一步着法
     *
     * 判定优先级：CHECK(照将) > 要抽吃/CHASE(捉子) > 拦/献/兑/做杀/IDLE(闲着)
     *
     * @param boardBefore 走子前的棋盘
     * @param move 走法
     * @param boardAfter 走子后的棋盘
     * @return 着法分类
     *
     * 【关于"做杀"归为闲着的说明】
     * 亚洲象棋联合会棋例中，"做杀"（制造杀棋威胁）与"长杀"（连续制造杀棋威胁）
     * 均归为"闲"着处理，因为守方可以坚持不变——杀棋威胁未必能兑现，守方有权在
     * 循环中等待攻势化解。因此本方法将"做杀"判定为 IDLE(闲着)，与长拦、长献、
     * 长兑同理作和。
     */
    private fun classifyMove(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): MoveCategory {
        // 1. 照将：走完后对方被将军
        // boardAfter.currentPlayer 是走子方的对手（已切换走棋方）
        if (boardAfter.isInCheck(boardAfter.currentPlayer)) {
            return MoveCategory.CHECK
        }

        // 2. 捉子：走子后产生新的吃子威胁（攻击对方将/帅以外的棋子）
        //    但将、帅、兵、卒长捉不犯例（亚洲棋例：将帅兵卒可以长捉）
        if (move.piece.type == PieceType.KING || move.piece.type == PieceType.PAWN) {
            // 将帅兵卒的捉不记为犯例着法，归为闲着
            // 但仍需检测是否真的在捉（用于后续分析）
            if (isChasing(boardBefore, move, boardAfter)) {
                Log.d(TAG, "    将帅兵卒捉子（不犯例）: ${move.piece.type}")
                return MoveCategory.IDLE
            }
        }

        if (isChasing(boardBefore, move, boardAfter)) {
            return MoveCategory.CHASE
        }

        // 3. 要抽吃：走子腾开线路，使其后方的友方滑行棋子（车/炮）可在下一着
        //    吃去对方非将棋子，构成"发现的抽吃威胁"。属攻击性着法，归为捉子(CHASE)。
        if (isDiscoveredAttackChase(boardBefore, move, boardAfter)) {
            return MoveCategory.CHASE
        }

        // 4. 拦：走子拦截敌方棋子的攻击线路（长拦作和 → 闲）
        if (isBlockingMove(boardBefore, move, boardAfter)) {
            return MoveCategory.IDLE
        }

        // 5. 献：走子至可被敌方吃去之处而献出（非互捉等值），长献作和 → 闲
        if (isSacrificeOffer(boardBefore, move, boardAfter)) {
            return MoveCategory.IDLE
        }

        // 6. 兑：走子攻击等值敌子且对方亦可反吃，长兑作和 → 闲
        if (isExchange(boardBefore, move, boardAfter)) {
            return MoveCategory.IDLE
        }

        // 7. 做杀：走子制造此前不存在的杀棋威胁。
        //    亚洲棋例中做杀/长杀作闲（守方可以坚持），故归为 IDLE。
        if (isMatingThreat(boardBefore, move, boardAfter)) {
            return MoveCategory.IDLE
        }

        // 8. 闲着（包括拦、献、兑、杀、闲等守方着法）
        return MoveCategory.IDLE
    }

    /**
     * 检测是否为捉子
     *
     * 捉着定义：走子攻击对方"将"、"帅"以外之任何一子，企图于下一着吃去之者
     *
     * 判定方法：
     * 1. 获取走子前，走子方能攻击的对方棋子位置集合（将/帅除外）
     * 2. 获取走子后，走子方能攻击的对方棋子位置集合（将/帅除外）
     * 3. 如果走子后有新的攻击目标（之前没有的），则为捉
     *
     * 注意：使用 getLegalMoves 来获取攻击目标，这样会自动排除被牵制
     * （pinned）棋子的无效攻击，符合棋规中"假根"和"牵制"的概念
     */
    private fun isChasing(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Boolean {
        val attackerColor = move.piece.color

        // ★ 先看走动的这枚棋子本身：落点是否新攻击到对方的非将棋子。
        //   不能只算"全side攻击集合差集"：被将军时，走子前的合法着法只剩应将着，
        //   攻击集合被压得极小；一步"支士解将"会凭空多出好几个吃子目标，被误判成捉。
        val moverBefore = attackTargetsOf(boardBefore, move.from, move.piece)
        val moverAfter = attackTargetsOf(boardAfter, move.to, move.piece)
        val newByMover = moverAfter - moverBefore
        if (newByMover.isNotEmpty()) {
            Log.d(TAG, "    捉子检测(走子本身): 新增攻击目标=${newByMover.map { it.toUci() }}")
            return true
        }

        // 被将军时，走子前的攻击集合不完整，再算全side差集只会产生假捉。
        if (boardBefore.isInCheck(attackerColor)) return false

        // 获取走子前，走子方能攻击的对方棋子位置（将/帅除外）
        val attacksBefore = getAttackTargets(boardBefore, attackerColor)

        // 获取走子后，走子方能攻击的对方棋子位置（将/帅除外）
        val attacksAfter = getAttackTargets(boardAfter, attackerColor)

        // 如果走子后有新的攻击目标（之前没有的），则为捉
        val newAttacks = attacksAfter - attacksBefore
        if (newAttacks.isNotEmpty()) {
            Log.d(TAG, "    捉子检测: 新增攻击目标=${newAttacks.map { it.toUci() }}")
            return true
        }

        return false
    }

    /**
     * 单枚棋子在当前局面下能吃到哪些敌方非将棋子（不做"己方是否被将军"过滤）。
     *
     * <p>为什么单独算：getLegalMoves 在己方被将军时只返回应将着，拿它做攻击集合差集
     * 会把"解将"误判成捉子。这里按棋子本身的走法扫描，只用于循环棋例的捉子判定。</p>
     */
    private fun attackTargetsOf(board: Board, from: Position, piece: Piece): Set<Position> {
        val out = mutableSetOf<Position>()
        val enemy = piece.color.opponent()

        fun add(r: Int, c: Int) {
            if (r !in 0..9 || c !in 0..8) return
            val p = board.getPiece(Position(r, c)) ?: return
            if (p.color == enemy && p.type != PieceType.KING) out.add(Position(r, c))
        }

        when (piece.type) {
            PieceType.ROOK, PieceType.CANNON -> {
                for ((dr, dc) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                    rawCaptureTargetInDir(board, from, piece.type, dr, dc, enemy)?.let { out.add(it) }
                }
            }
            PieceType.KNIGHT -> {
                val jumps = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
                for ((dr, dc) in jumps) {
                    val legR = from.row + if (dr == 2) 1 else if (dr == -2) -1 else 0
                    val legC = from.col + if (dc == 2) 1 else if (dc == -2) -1 else 0
                    if (board.getPiece(Position(legR, legC)) != null) continue
                    add(from.row + dr, from.col + dc)
                }
            }
            PieceType.PAWN -> {
                val forward = if (piece.color == PieceColor.RED) -1 else 1
                add(from.row + forward, from.col)
                val crossed = if (piece.color == PieceColor.RED) from.row <= 4 else from.row >= 5
                if (crossed) {
                    add(from.row, from.col - 1)
                    add(from.row, from.col + 1)
                }
            }
            PieceType.ADVISOR -> {
                for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
                    val r = from.row + dr
                    val c = from.col + dc
                    if (!inPalace(piece.color, r, c)) continue
                    add(r, c)
                }
            }
            PieceType.ELEPHANT -> {
                for ((dr, dc) in listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2)) {
                    val eyeR = from.row + dr / 2
                    val eyeC = from.col + dc / 2
                    if (board.getPiece(Position(eyeR, eyeC)) != null) continue
                    add(from.row + dr, from.col + dc)
                }
            }
            PieceType.KING -> {
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    add(from.row + dr, from.col + dc)
                }
            }
        }
        return out
    }

    private fun inPalace(color: PieceColor, row: Int, col: Int): Boolean {
        if (col !in 3..5) return false
        return if (color == PieceColor.RED) row in 7..9 else row in 0..2
    }

    /**
     * 获取某方能攻击的对方棋子位置（将/帅除外）
     *
     * 使用 getLegalMoves 获取合法走法，从中筛选出能吃子的走法
     * 这样自动排除了被牵制（pinned）棋子的无效攻击
     *
     * @param board 棋盘
     * @param attackerColor 攻击方颜色
     * @return 能被攻击方吃掉的位置集合（将/帅位置不包含在内）
     */
    private fun getAttackTargets(
        board: Board,
        attackerColor: PieceColor
    ): Set<Position> {
        val targets = mutableSetOf<Position>()
        val moves = board.getLegalMoves(attackerColor)
        for (m in moves) {
            if (m.captured != null) {
                val capturedPiece = board.getPiece(m.to)
                // 排除将/帅（攻击将/帅是照将，不是捉）
                if (capturedPiece != null && capturedPiece.type != PieceType.KING) {
                    targets.add(m.to)
                }
            }
        }
        return targets
    }

    /**
     * 获取走子后新增的攻击目标位置（用于追踪捉子目标）
     * 返回走子后能攻击但走子前不能攻击的对方棋子位置
     */
    private fun getChaseTargets(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Set<Position> {
        val attackerColor = move.piece.color
        val attacksBefore = getAttackTargets(boardBefore, attackerColor)
        val attacksAfter = getAttackTargets(boardAfter, attackerColor)
        return attacksAfter - attacksBefore
    }

    // ═══════════════════════════════════════════════════════════════
    //  以下为补充着法判定：要抽吃 / 拦 / 献 / 兑 / 做杀
    // ═══════════════════════════════════════════════════════════════

    /**
     * 棋子估值（用于"兑"的等值判断）
     * 将帅不参与兑子估值，返回 0。
     */
    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.ROOK -> 9
        PieceType.CANNON -> 5
        PieceType.KNIGHT -> 4
        PieceType.ELEPHANT -> 2
        PieceType.ADVISOR -> 2
        PieceType.PAWN -> 1
        PieceType.KING -> 0
    }

    /**
     * 要抽吃（发现的抽吃威胁）检测
     *
     * 走子腾开原位置后，其后方同线上的友方滑行棋子（车/炮）可在下一着吃去
     * 对方非将棋子，即构成"要抽吃"。属攻击性着法，归为捉子(CHASE)。
     *
     * 注意：本检测使用原始线路扫描（不经过合法走法过滤），可捕捉 isChasing 因
     * 牵制/假根过滤而漏判的"抽吃威胁"。
     */
    private fun isDiscoveredAttackChase(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Boolean {
        val attackerColor = move.piece.color
        val opponentColor = attackerColor.opponent()
        val from = move.from

        val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        for ((dr, dc) in dirs) {
            // 沿 (dr,dc) 从 from 出发，找走子前第一个棋子（潜在的抽吃发起者）
            val discoverer = findFirstPieceInDir(boardBefore, from, dr, dc) ?: continue
            val (discovererPos, discovererPiece) = discoverer
            if (discovererPiece.color != attackerColor) continue
            // 仅车/炮这类滑行棋子能形成抽吃
            if (discovererPiece.type != PieceType.ROOK &&
                discovererPiece.type != PieceType.CANNON) continue

            // 反方向（由发起者指向 from 及更远处）
            val targetBefore = rawCaptureTargetInDir(
                boardBefore, discovererPos, discovererPiece.type,
                -dr, -dc, opponentColor
            )
            val targetAfter = rawCaptureTargetInDir(
                boardAfter, discovererPos, discovererPiece.type,
                -dr, -dc, opponentColor
            )
            // 走子后新增了可吃目标（之前没有）→ 抽吃威胁
            if (targetAfter != null && targetAfter != targetBefore) {
                Log.d(TAG, "    要抽吃检测: ${discovererPiece.type}(${discovererPos.toUci()}) " +
                        "抽吃威胁目标=${targetAfter.toUci()}")
                return true
            }
        }
        return false
    }

    /**
     * 沿方向 (dr,dc) 扫描，返回滑行棋子(车/炮)在该方向上能吃到的对方非将棋子位置。
     * 车取第一个遇到的棋子；炮需隔一个炮架后吃下一子。
     * 不经过合法性过滤（用于威胁判定）。
     */
    private fun rawCaptureTargetInDir(
        board: Board,
        pos: Position,
        type: PieceType,
        dr: Int,
        dc: Int,
        enemyColor: PieceColor
    ): Position? {
        var r = pos.row + dr
        var c = pos.col + dc
        when (type) {
            PieceType.ROOK -> {
                while (r in 0..9 && c in 0..8) {
                    val p = board.getPiece(Position(r, c))
                    if (p != null) {
                        return if (p.color == enemyColor && p.type != PieceType.KING)
                            Position(r, c) else null
                    }
                    r += dr
                    c += dc
                }
                return null
            }
            PieceType.CANNON -> {
                var screen = false
                while (r in 0..9 && c in 0..8) {
                    val p = board.getPiece(Position(r, c))
                    if (p != null) {
                        if (!screen) {
                            screen = true // 第一个棋子作为炮架
                        } else {
                            return if (p.color == enemyColor && p.type != PieceType.KING)
                                Position(r, c) else null
                        }
                    }
                    r += dr
                    c += dc
                }
                return null
            }
            else -> return null
        }
    }

    /**
     * 拦（拦截）检测
     *
     * 走子后，移动棋子位于敌方"车"与己方非将棋子的同一直线上，拦截了此前畅通的
     * 攻击线路。长拦作和 → 归为闲着(IDLE)。
     * （炮需炮架，拦截语义不同，此处仅处理"车"。）
     */
    private fun isBlockingMove(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Boolean {
        val moverColor = move.piece.color
        val enemyColor = moverColor.opponent()
        val to = move.to
        val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        for ((dr, dc) in dirs) {
            val enemy = findFirstPieceInDir(boardAfter, to, dr, dc) ?: continue
            val (attackerPos, attackerPiece) = enemy
            if (attackerPiece.color != enemyColor) continue
            if (attackerPiece.type != PieceType.ROOK) continue
            val friendly = findFirstPieceInDir(boardAfter, to, -dr, -dc) ?: continue
            val (friendlyPos, friendlyPiece) = friendly
            if (friendlyPiece.color != moverColor) continue
            if (friendlyPiece.type == PieceType.KING) continue // 拦将攻击属照将范畴
            // 走子前该线是否畅通（to 处原为空且无其他阻挡）
            if (wasLineOpen(boardBefore, attackerPos, friendlyPos)) {
                Log.d(TAG, "    拦截检测: ${move.piece.type}(${to.toUci()}) 拦截 " +
                        "${attackerPiece.type}(${attackerPos.toUci()}) 对 " +
                        "${friendlyPiece.type}(${friendlyPos.toUci()}) 的攻击")
                return true
            }
        }
        return false
    }

    /**
     * 献（献子）检测
     *
     * 走子至可被敌方吃去之处而献出自身，且并非"互捉等值"的兑子情形。
     * 长献作和 → 归为闲着(IDLE)。
     */
    private fun isSacrificeOffer(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Boolean {
        val enemyColor = move.piece.color.opponent()
        val to = move.to
        val enemyCapturesMoved = boardAfter.getLegalMoves(enemyColor)
            .any { it.to == to && it.captured != null }
        if (!enemyCapturesMoved) return false
        // 排除互捉等值的兑子情形，交由 isExchange 处理
        if (isMutualExchange(boardAfter, move)) return false
        Log.d(TAG, "    献子检测: ${move.piece.type}(${to.toUci()}) 献出可被敌方吃去")
        return true
    }

    /**
     * 兑（兑子）检测
     *
     * 走子攻击等值敌子，且对方亦可反吃该走子，构成互捉等值兑换。
     * 长兑作和 → 归为闲着(IDLE)。
     */
    private fun isExchange(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Boolean {
        if (isMutualExchange(boardAfter, move)) {
            Log.d(TAG, "    兑子检测: ${move.piece.type}(${move.to.toUci()}) 与等值敌子互捉")
            return true
        }
        return false
    }

    /**
     * 判定走子后是否构成"互捉等值"的兑子关系：
     * 移动棋子可吃等值敌子，且该移动棋子亦被敌方吃去。
     */
    private fun isMutualExchange(boardAfter: Board, move: Move): Boolean {
        val moverColor = move.piece.color
        val enemyColor = moverColor.opponent()
        val to = move.to
        val movedValue = pieceValue(move.piece.type)
        if (movedValue == 0) return false // 将帅不兑

        val enemyCapturesMoved = boardAfter.getLegalMoves(enemyColor)
            .any { it.to == to && it.captured != null }
        if (!enemyCapturesMoved) return false

        return boardAfter.getLegalMoves(moverColor).any { m ->
            val cap = m.captured ?: return@any false
            m.from == to &&
                    cap.type != PieceType.KING &&
                    pieceValue(cap.type) == movedValue
        }
    }

    /**
     * 做杀（制造杀棋威胁）检测
     *
     * 走子制造了此前不存在的杀棋威胁（下一着可将死对方）。
     * 亚洲棋例中做杀/长杀作闲（守方可以坚持），故归为闲着(IDLE)。
     */
    private fun isMatingThreat(
        boardBefore: Board,
        move: Move,
        boardAfter: Board
    ): Boolean {
        val moverColor = move.piece.color
        if (!hasMateInOne(boardAfter, moverColor)) return false
        // 之前已存在杀棋威胁则不算新的"做杀"
        if (hasMateInOne(boardBefore, moverColor)) return false
        Log.d(TAG, "    做杀检测: 走子后形成新的杀棋威胁（亚洲棋例做杀/长杀作闲）")
        return true
    }

    /**
     * 判定指定方是否存在"一步将死"对方的着法
     */
    private fun hasMateInOne(board: Board, attackerColor: PieceColor): Boolean {
        val opponentColor = attackerColor.opponent()
        for (m in board.getLegalMoves(attackerColor)) {
            val captured = board.getPiece(m.to)
            board.setPiece(m.to, m.piece)
            board.setPiece(m.from, null)
            val mate = board.isInCheck(opponentColor) &&
                    board.getLegalMoves(opponentColor).isEmpty()
            board.setPiece(m.from, m.piece)
            board.setPiece(m.to, captured)
            if (mate) return true
        }
        return false
    }

    /**
     * 沿方向 (dr,dc) 从 from 出发，返回第一个遇到的棋子及其位置
     */
    private fun findFirstPieceInDir(
        board: Board,
        from: Position,
        dr: Int,
        dc: Int
    ): Pair<Position, Piece>? {
        var r = from.row + dr
        var c = from.col + dc
        while (r in 0..9 && c in 0..8) {
            val pos = Position(r, c)
            val p = board.getPiece(pos)
            if (p != null) return pos to p
            r += dr
            c += dc
        }
        return null
    }

    /**
     * 判断两点是否在同一直线上，且棋盘上该两点之间无任何棋子（线路畅通）
     */
    private fun wasLineOpen(board: Board, posA: Position, posB: Position): Boolean {
        if (posA.row != posB.row && posA.col != posB.col) return false
        return if (posA.row == posB.row) {
            val row = posA.row
            val minC = minOf(posA.col, posB.col)
            val maxC = maxOf(posA.col, posB.col)
            (minC + 1 until maxC).none { board.getPiece(Position(row, it)) != null }
        } else {
            val col = posA.col
            val minR = minOf(posA.row, posB.row)
            val maxR = maxOf(posA.row, posB.row)
            (minR + 1 until maxR).none { board.getPiece(Position(it, col)) != null }
        }
    }
}
