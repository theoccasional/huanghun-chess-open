package com.yk.xiangqi.notation;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * 绝对坐标着法到标准中文记谱的转换。
 *
 * <p>红方纵线自右向左记“一”至“九”，黑方自左向右记 1 至 9；同一直线上出现同兵种
 * 多子时改用“前/中/后”限定。车、炮、兵卒、将帅沿纵线行走时进退记步数，马、相象、
 * 仕士斜行时进退记目标纵线。</p>
 */
public final class Notation {
    private static final char[] RED_DIGITS = {'一', '二', '三', '四', '五', '六', '七', '八', '九'};

    private Notation() {
    }

    /** 返回 {@code position} 局面下 {@code move} 的中文记谱；局面缺失时回退为 UCI。 */
    public static String chinese(Position position, Move move) {
        if (position == null || move == null)
            return move == null ? "" : move.uci();
        PieceType[] types = new PieceType[90];
        Side[] sides = new Side[90];
        position.forEachPiece((square, side, type) -> {
            types[square] = type;
            sides[square] = side;
        });
        PieceType type = types[move.from];
        if (type == null)
            return move.uci();
        return chinese(sides[move.from], type, move.from, move.to, types, sides);
    }

    /**
     * 由“UCI 主变 + 起始局面”翻译整条主变；非法着直接保留 UCI，避免中断整条显示。
     */
    public static List<String> chineseVariation(Position position, List<String> moves) {
        List<String> out = new ArrayList<>(moves.size());
        Position current = position;
        for (String uci : moves) {
            if (!Move.isUciCoordinate(uci)) {
                out.add(uci);
                continue;
            }
            Move move = Move.parse(uci);
            if (current.isLegal(move)) {
                out.add(chinese(current, move));
                current = current.play(move);
            } else {
                out.add(uci);
            }
        }
        return out;
    }

    private static String chinese(Side side, PieceType type, int from, int to, PieceType[] types, Side[] sides) {
        int fromFile = Board.file(from), fromRank = Board.rank(from);
        int toFile = Board.file(to), toRank = Board.rank(to);
        StringBuilder out = new StringBuilder(4);
        String qualifier = qualifier(type, side, fromFile, fromRank, types, sides);
        if (qualifier == null)
            out.append(pieceName(type, side)).append(fileNumber(side, fromFile));
        else
            out.append(qualifier).append(pieceName(type, side));
        if (toRank == fromRank)
            out.append('平').append(fileNumber(side, toFile));
        else {
            boolean forward = side == Side.RED ? toRank > fromRank : toRank < fromRank;
            out.append(forward ? '进' : '退');
            out.append(straight(type) ? digit(side, Math.abs(toRank - fromRank)) : fileNumber(side, toFile));
        }
        return out.toString();
    }

    /**
     * 同一直线出现两个以上同兵种时返回“前/中/后”等限定；否则返回 {@code null}，改用起始纵线。
     */
    private static String qualifier(PieceType type, Side side, int file, int rank, PieceType[] types, Side[] sides) {
        List<Integer> same = new ArrayList<>(4);
        for (int square = file; square < 90; square += 9)
            if (types[square] == type && sides[square] == side)
                same.add(square);
        if (same.size() <= 1)
            return null;
        // 靠近对方底线者为“前”：红方行号大者在前，黑方行号小者在前。
        same.sort((first, second) -> side == Side.RED
                ? Integer.compare(Board.rank(second), Board.rank(first))
                : Integer.compare(Board.rank(first), Board.rank(second)));
        int index = same.indexOf(rank * 9 + file);
        if (index < 0)
            return null;
        int count = same.size();
        if (count == 2)
            return index == 0 ? "前" : "后";
        if (count == 3)
            return index == 0 ? "前" : index == 1 ? "中" : "后";
        // 四个以上只可能是兵卒，按自前向后的序号限定。
        return String.valueOf(digit(side, index + 1));
    }

    private static boolean straight(PieceType type) {
        return type == PieceType.ROOK || type == PieceType.CANNON || type == PieceType.PAWN || type == PieceType.KING;
    }

    private static String pieceName(PieceType type, Side side) {
        return switch (type) {
            case ROOK -> "车";
            case KNIGHT -> "马";
            case CANNON -> side == Side.RED ? "炮" : "砲";
            case BISHOP -> side == Side.RED ? "相" : "象";
            case ADVISOR -> side == Side.RED ? "仕" : "士";
            case KING -> side == Side.RED ? "帅" : "将";
            case PAWN -> side == Side.RED ? "兵" : "卒";
        };
    }

    /** 纵线序号：红方以右侧为一，黑方以左侧为 1。 */
    private static String fileNumber(Side side, int file) {
        return side == Side.RED ? String.valueOf(digit(side, 9 - file)) : String.valueOf(file + 1);
    }

    private static char digit(Side side, int value) {
        int index = Math.max(1, Math.min(9, value)) - 1;
        return side == Side.RED ? RED_DIGITS[index] : (char) ('1' + index);
    }
}