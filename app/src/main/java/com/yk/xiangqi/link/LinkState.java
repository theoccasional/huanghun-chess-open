package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

/**
 * 连线的纯状态归约。
 *
 * <p>输入当前应用局面与识别棋盘，输出下一连线状态和应用动作；不接触 Android 图像、
 * ONNX、线程或无障碍服务。</p>
 */
public record LinkState(Side bottomSide, Side newGameSide, Phase phase, long writebackDeadlineMs,
                        int stallCount, long stallSignature) {
    public enum Phase {INITIAL, NORMAL, WAITING_WRITEBACK, DESYNCED}

    private static final int REPAIR_DIFFERENCE = 1;
    /**
     * 观测棋盘连续多少拍给出同一条「无法用走子解释」的结论后，才敢按它重建局面。
     *
     * <p>一拍之差大多是动画残影、单帧误读，忍一忍就过去了；同样的差异连着两拍仍在，
     * 说明它已经稳稳黏在局面上——多半是「凭空多出的子/漏掉的子」。两拍足以把这类
     * 漏子清掉，又不至于把一过性的抖动当成真变化。</p>
     */
    private static final int STALL_REPAIR_FRAMES = 2;
    /**
     * 走子容差：动画半途、棋子残影或单帧误读产生的少量结构误差（找不到任何一方合法走子）
     * 不再直接失步，而是当作“局面正在变化”跳过该帧，等下一帧落地后照常归约。
     * 只有超出该容差的无法解释差异才判失步，避免“卡在半腰”。
     */
    private static final int MOVEMENT_TOLERANCE = 3;
    private static final long WRITEBACK_WAIT_MS = 3_000L;
    private static final ObservedBoard STANDARD_START = ObservedBoard.from(Position.start());

    public static LinkState start(Side bottomSide, Side newGameSide) {
        return new LinkState(bottomSide, newGameSide, Phase.INITIAL, 0L, 0, 0L);
    }

    public LinkState withNewGameSide(Side side) {
        return new LinkState(bottomSide, side, phase, writebackDeadlineMs, stallCount, stallSignature);
    }

    public LinkState awaitWriteback(long nowMs) {
        if (phase == Phase.DESYNCED)
            return this;
        return new LinkState(bottomSide, newGameSide, Phase.WAITING_WRITEBACK,
            nowMs + WRITEBACK_WAIT_MS, 0, 0L);
    }

    public LinkState cancelWriteback() {
        return phase == Phase.WAITING_WRITEBACK
            ? new LinkState(bottomSide, newGameSide, Phase.NORMAL, 0L, 0, 0L)
            : this;
    }

    public LinkState stopRecognition() {
        return new LinkState(bottomSide, newGameSide, Phase.DESYNCED, 0L, 0, 0L);
    }

    /** 用户同步当前识别棋盘时，直接重建应用局面。 */
    public LinkResult synchronize(ObservedBoard screenBoard, Side sideToMove) {
        return reset(screenBoard, sideToMove);
    }

    public LinkResult reduce(Position current, ObservedBoard screenBoard, long nowMs) {
        if (phase == Phase.DESYNCED)
            // ★ 失步不再永久冻结：下一帧就把应用局面重新对齐到当前屏幕。
            //   用户在大棋盘上怎么走，小棋盘就怎么跟随，无需手动校准；
            //   行棋方沿用应用当前的行棋方，保证接续自然。
            return reset(screenBoard, current.sideToMove());
        if (phase == Phase.INITIAL)
            return reset(screenBoard, newGameSide);

        ObservedBoard observed = screenBoard.orientForBottom(bottomSide);
        ObservedBoard currentBoard = ObservedBoard.from(current);
        int difference = observed.structureDifference(currentBoard);
        if (phase == Phase.WAITING_WRITEBACK) {
            if (difference == 0)
                return normal(LinkAction.NONE);
            Move response = findMove(current, currentBoard, observed, difference, 0);
            if (response != null)
                return normal(LinkAction.play(response));
            if (nowMs < writebackDeadlineMs)
                return result(LinkAction.NONE);
        }

        if (difference == 0)
            return normal(LinkAction.NONE);

        Move move = findMove(current, currentBoard, observed, difference, 0);
        if (move != null)
            return normal(LinkAction.play(move));
        if (standardStartBottom(screenBoard) != null)
            return reset(screenBoard, newGameSide);

        move = findMove(current, currentBoard, observed, difference, REPAIR_DIFFERENCE);
        if (move != null)
            return normal(LinkAction.play(move));
        if (difference > MOVEMENT_TOLERANCE)
            return new LinkResult(stopRecognition(), LinkAction.DESYNCED);

        // ★ 差异落在容差内、却找不到任何合法走子能解释：多半是一颗“认偏了格”的车/兵、
        //   或凭空多出/漏掉的子，牢牢黏在局面上，怎么走都对不上。单拍可能是动画残影，
        //   先记下这条差异；同一差异连着两拍都还在、且当前观测棋盘本身自洽
        //   （将帅齐全、无子力超额）时，就按它重建——把黏住的漏子清干净，避免永久卡死。
        long signature = differenceSignature(observed, currentBoard);
        int stalls = signature == stallSignature ? stallCount + 1 : 1;
        if (stalls >= STALL_REPAIR_FRAMES && observed.canBuildPosition() && observed.surplusCount() == 0)
            return reset(screenBoard, current.sideToMove());
        return stalled(stalls, signature);
    }

    /**
     * 两次观测与当前局面之间「无法用走子解释」的差异签名：把每个结构不同的格子及其观测
     * 三态混成一个整数。同一处漏子跨帧签名不变，据此把它与一过性的抖动区分开。
     */
    private static long differenceSignature(ObservedBoard observed, ObservedBoard currentBoard) {
        long hash = 1_125_899_906_842_597L;
        for (int square = 0; square < Board.SQUARES; square++) {
            int observedState = observed.structureAt(square);
            if (observedState == currentBoard.structureAt(square))
                continue;
            hash = hash * 31 + square * 3L + observedState;
        }
        return hash;
    }

    static Side standardStartBottom(ObservedBoard screenBoard) {
        if (screenBoard.orientForBottom(Side.RED).structureDifference(STANDARD_START) == 0)
            return Side.RED;
        if (screenBoard.orientForBottom(Side.BLACK).structureDifference(STANDARD_START) == 0)
            return Side.BLACK;
        return null;
    }

    private LinkResult reset(ObservedBoard screenBoard, Side sideToMove) {
        Side standardBottom = standardStartBottom(screenBoard);
        if (standardBottom != null)
            return reset(standardBottom, STANDARD_START.toPosition(sideToMove));
        if (!screenBoard.canBuildPosition())
            return failed();
        Side bottom = inferBottomSide(screenBoard);
        return reset(bottom, screenBoard.orientForBottom(bottom).toPosition(sideToMove));
    }

    private LinkResult reset(Side bottom, Position position) {
        LinkState next = new LinkState(bottom, newGameSide, Phase.NORMAL, 0L, 0, 0L);
        return new LinkResult(next, LinkAction.reset(position));
    }

    private LinkResult failed() {
        return new LinkResult(stopRecognition(), LinkAction.DESYNCED);
    }

    private LinkResult normal(LinkAction action) {
        return new LinkResult(new LinkState(bottomSide, newGameSide, Phase.NORMAL, 0L, 0, 0L), action);
    }

    /**
     * 差异暂时无法解释、但仍在容差内：保留局面不动，只把这条差异的连续出现次数记进状态，
     * 供下一拍判断它是不是已经稳稳黏在局面上。
     */
    private LinkResult stalled(int stalls, long signature) {
        return new LinkResult(
            new LinkState(bottomSide, newGameSide, Phase.NORMAL, 0L, stalls, signature), LinkAction.NONE);
    }

    private LinkResult result(LinkAction action) {
        return new LinkResult(this, action);
    }

    private static Move findMove(Position current, ObservedBoard currentBoard, ObservedBoard observed,
                                 int currentDifference, int allowedDifference) {
        Move result = null;
        int movingSide = current.sideToMove() == Side.RED ? 1 : 2;
        for (Move move : current.legalMoves()) {
            // 起终点定义这一步；补救只能忽略其余不动格的一处结构误差。
            if (observed.structureAt(move.from) != 0 || observed.structureAt(move.to) != movingSide)
                continue;
            int difference = currentDifference;
            difference -= mismatch(observed.structureAt(move.from), currentBoard.structureAt(move.from));
            difference -= mismatch(observed.structureAt(move.to), currentBoard.structureAt(move.to));
            if (difference > allowedDifference)
                continue;
            if (result != null)
                return null;
            result = move;
        }
        return result;
    }

    private static int mismatch(int first, int second) {
        return first == second ? 0 : 1;
    }

    private static Side inferBottomSide(ObservedBoard board) {
        int redKing = -1;
        int blackKing = -1;
        for (int square = 0; square < Board.SQUARES; square++) {
            if (board.at(square) == 1) redKing = square;
            if (board.at(square) == 8) blackKing = square;
        }
        if (redKing < 0 || blackKing < 0)
            return Side.RED;
        return Board.rank(redKing) > Board.rank(blackKing) ? Side.RED : Side.BLACK;
    }
}
