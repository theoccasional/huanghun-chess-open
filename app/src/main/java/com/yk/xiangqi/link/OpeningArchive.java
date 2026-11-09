package com.yk.xiangqi.link;

import com.pikafish.chess.qipu.EccoNamer;
import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

/**
 * 观棋模式的「走子存档」——只记录用户实打实在棋盘上走出的每一着，用来判定当前是
 * 什么开局（ECCO）。
 *
 * <p>它把每着棋增量转成 ECCO 的 C2.5 记谱串（如 {@code C2.5N8+7…}），再交给
 * {@link EccoNamer} 反查编号与开局名。因为存档是独立累加的，校准/轻微刷新把局面重置
 * 回当前屏幕时也<b>不会</b>清空——开局名因此常驻，不会变成「7 秒记忆的鱼」。
 * 只有确认走回了标准开局（真正的新对局）才会清空。</p>
 *
 * <p>不显示任何东西，纯后台存档；界面只读 {@link #label()} 的结果。</p>
 */
public final class OpeningArchive {

    /** 开局判定结果：ECCO 编号 + 开局名 + 变例名。 */
    public static final class Label {
        public final String code;
        public final String opening;
        public final String variation;

        Label(String code, String opening, String variation) {
            this.code = code;
            this.opening = opening;
            this.variation = variation;
        }
    }

    private final java.util.List<String> tokens = new java.util.ArrayList<>();
    /**
     * 与 {@link #tokens} 同步的着法序列。
     *
     * <p>存着法本身（而不是只存 C2.5 串）才能<b>整段撤销</b>：用户悔棋、或不按引擎推荐走
     * 自己的着法时，作废的那一段要从棋谱里取消记录，只留用户真正承认的前缀。</p>
     */
    private final java.util.List<Move> moves = new java.util.ArrayList<>();
    /** 与存档同步重放出的局面，用来核对存档是否与棋盘一致。 */
    private Position replay = Position.start();

    /** 追加用户走出的一个着法（含该着棋子兵种与颜色）。 */
    public synchronized void append(Move move, PieceType type, Side side) {
        if (move == null || type == null || side == null)
            return;
        int fromRow = Board.RANKS - 1 - Board.rank(move.from);
        int fromCol = Board.file(move.from);
        int toRow = Board.RANKS - 1 - Board.rank(move.to);
        int toCol = Board.file(move.to);
        String token = EccoNamer.INSTANCE.toC25(fromRow, fromCol, toRow, toCol,
            pieceType(type), side == Side.RED
                ? com.pikafish.chess.model.PieceColor.RED
                : com.pikafish.chess.model.PieceColor.BLACK);
        if (token == null)
            return;
        tokens.add(token);
        moves.add(move);
        try {
            replay = replay.play(move);
        } catch (IllegalArgumentException ignored) {
            // 只作判定用；这一步与重放局面不合法说明存档已错位，交由调用方按 matches 判定后重建。
        }
    }

    /** 真正的新对局（回到标准开局）时清空存档。 */
    public synchronized void clear() {
        tokens.clear();
        moves.clear();
        replay = Position.start();
    }

    /** 已记录的着数。 */
    public synchronized int size() {
        return moves.size();
    }

    /** 存档前 {@code keep} 着走完后的局面；供“把作废的着法整段撤销、改记用户走法”定位回退点。 */
    public synchronized Position prefixPosition(int keep) {
        int count = Math.max(0, Math.min(keep, moves.size()));
        Position result = Position.start();
        for (int index = 0; index < count; index++) {
            try {
                result = result.play(moves.get(index));
            } catch (IllegalArgumentException ignored) {
                return result;
            }
        }
        return result;
    }

    /**
     * 只保留前 {@code keep} 着，其余整段撤销。
     *
     * <p>用户悔棋、或用户走了和引擎推荐不一样的一着时，那一步是“作废”的，
     * 记谱上直接取消记录即可——棋谱此后从头接上用户真实走出来的着法。</p>
     */
    public synchronized void truncate(int keep) {
        int count = Math.max(0, Math.min(keep, moves.size()));
        while (moves.size() > count)
            moves.remove(moves.size() - 1);
        while (tokens.size() > count)
            tokens.remove(tokens.size() - 1);
        replay = prefixPosition(count);
    }

    /** 完整的 C2.5 记谱串。 */
    private synchronized String c25() {
        return String.join("", tokens);
    }

    /** 存档重放到的局面是否与给定局面的棋盘一致（只比棋盘，不比行棋方——
     *  这样即使用户把「新局先手」设错，也不影响存档与棋盘的对齐判断）。 */
    public synchronized boolean matches(Position position) {
        if (position == null)
            return false;
        ObservedBoard here = ObservedBoard.from(replay);
        ObservedBoard there = ObservedBoard.from(position);
        for (int square = 0; square < Board.SQUARES; square++)
            if (here.at(square) != there.at(square))
                return false;
        return true;
    }

    public synchronized boolean isEmpty() {
        return moves.isEmpty();
    }

    /** 存档重放到的局面（只读），供“顺着已有存档往前补着法”时作为搜索起点。 */
    public synchronized Position replayPosition() {
        return replay;
    }

    /** 依据已存档的走子序列判定开局；判不出返回 null（界面据此显示占位符）。 */
    public synchronized Label label() {
        String record = c25();
        if (record.isEmpty())
            return null;
        String code = EccoNamer.INSTANCE.eccoIndex(record);
        if (code == null)
            return null;
        kotlin.Pair<String, String> named = EccoNamer.INSTANCE.nameOf(code);
        if (named == null)
            return null;
        String opening = named.getFirst();
        if (opening == null || opening.isEmpty())
            return null;
        String variation = named.getSecond();
        if (variation == null)
            variation = "";
        return new Label(code, opening, variation);
    }

    private static com.pikafish.chess.model.PieceType pieceType(PieceType type) {
        return switch (type) {
            case ROOK -> com.pikafish.chess.model.PieceType.ROOK;
            case KNIGHT -> com.pikafish.chess.model.PieceType.KNIGHT;
            case BISHOP -> com.pikafish.chess.model.PieceType.ELEPHANT;
            case ADVISOR -> com.pikafish.chess.model.PieceType.ADVISOR;
            case KING -> com.pikafish.chess.model.PieceType.KING;
            case CANNON -> com.pikafish.chess.model.PieceType.CANNON;
            case PAWN -> com.pikafish.chess.model.PieceType.PAWN;
        };
    }
}