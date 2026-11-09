package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * 从标准开局出发，反推「走到目标局面」的着法前缀。
 *
 * <p>连线在开局中途才启动时，首次建盘（INITIAL 归约）会把屏幕上已经走过的头几着直接并进局面，
 * 走子存档因此从第 2 着才开始累加。ECCO 的 C2.5 串按「红黑交替」解读，缺了红方第一着后，
 * 黑方的着就被当成红方第一着读——「中炮对起马」于是被误判成「起马局」。</p>
 *
 * <p>这里用有界搜索把这段前缀补回来，让存档从第一着对齐。搜索只覆盖开局附近很浅的若干半回合，
 * 且每层都做「起点必须尚未归位」的强剪枝，实际是几百节点量级的小搜索，不会拖住连线线程。</p>
 */
final class OpeningReconstructor {

    /** 搜索深度上限（半回合）：开局判定用不着很深，4 着已覆盖绝大多数 ECCO 局面。 */
    private static final int MAX_PLY = 4;
    /** 节点预算：兜底防止异常局面下的组合爆炸，超预算即放弃（宁可显示「—」也不报错名）。
     *  它在主线程（走子回调）上跑，预算压小以保证任何局面下都是百毫秒级，绝不卡住界面。 */
    private static final int NODE_BUDGET = 6_000;

    private OpeningReconstructor() {
    }

    /** 返回从标准开局走到目标局面的最短着法前缀；不是从标准开局走出来的（或超出搜索深度）返回 null。 */
    static List<Move> prefix(Position target) {
        return path(Position.start(), target);
    }

    /**
     * 返回从 {@code from} 走到 {@code target} 的最短着法序列；走不到（或超出搜索深度）返回 null。
     *
     * <p>既用于「连线在开局中途才启动、补齐头几着」，也用于「校准把局面往前带了半步、
     * 顺着已有存档把新走出的着补上」——后者的起点是存档重放到的局面，因此补出的着法
     * 一定接在用户真实走子之后，不会打乱既有顺序、换掉开局名。</p>
     */
    static List<Move> path(Position from, Position target) {
        byte[] goal = snapshot(target);
        byte[] start = snapshot(from);
        // 迭代加深：先试浅层，保证拿到的是最短前缀，不会把「红—黑」两着错补成四着。
        for (int depth = 1; depth <= MAX_PLY; depth++) {
            List<Move> moves = new ArrayList<>();
            int[] budget = {NODE_BUDGET};
            if (search(from, start, goal, moves, 0, depth, budget))
                return moves;
        }
        return null;
    }

    private static boolean search(Position current, byte[] cells, byte[] goal,
                                  List<Move> path, int ply, int maxDepth, int[] budget) {
        if (same(cells, goal))
            return true;
        if (ply >= maxDepth || budget[0] <= 0)
            return false;
        // 每着最多改正两个格；剩余深度改不完就不必再搜。
        if (difference(cells, goal) > 2 * (maxDepth - ply))
            return false;
        for (Move move : current.legalMoves()) {
            if (--budget[0] <= 0)
                return false;
            // 剪枝：前缀里每一着都要把起点格腾空、让该子归位，所以只从「尚未归位的差异格」起子。
            if (cells[move.from] != ObservedBoard.EMPTY && cells[move.from] == goal[move.from])
                continue;
            Position next = current.play(move);
            path.add(move);
            if (search(next, snapshot(next), goal, path, ply + 1, maxDepth, budget))
                return true;
            path.remove(path.size() - 1);
        }
        return false;
    }

    private static byte[] snapshot(Position position) {
        ObservedBoard observed = ObservedBoard.from(position);
        byte[] cells = new byte[Board.SQUARES];
        for (int square = 0; square < Board.SQUARES; square++)
            cells[square] = observed.at(square);
        return cells;
    }

    private static boolean same(byte[] first, byte[] second) {
        for (int square = 0; square < Board.SQUARES; square++)
            if (first[square] != second[square])
                return false;
        return true;
    }

    private static int difference(byte[] first, byte[] second) {
        int count = 0;
        for (int square = 0; square < Board.SQUARES; square++)
            if (first[square] != second[square])
                count++;
        return count;
    }
}