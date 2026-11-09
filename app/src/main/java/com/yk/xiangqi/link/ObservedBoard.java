package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型一次识别出的 90 格、15 类紧凑棋盘。
 */
public final class ObservedBoard {
    public static final byte EMPTY = 0;
    public static final int LABELS = 15;
    /** 各兵种每方上限（按 kind 1..7 索引），合计恰好 16 子。 */
    private static final int[] LIMITS = {0, 1, 2, 2, 2, 2, 2, 5};
    private final byte[] cells;
    /**
     * 用户亲手指定过的格（本棋盘自己的坐标系）。
     *
     * <p>这些格的身份不容识别和合法性修正改动，否则「车被认成卒、用户改成车」之后，
     * {@link #legalized()} 会因为车超额而把它又换回卒，用户怎么点都改不动。</p>
     */
    private final boolean[] locked;

    public ObservedBoard(byte[] cells) {
        this(cells, null);
    }

    public ObservedBoard(byte[] cells, boolean[] locked) {
        if (cells == null || cells.length != Board.SQUARES)
            throw new IllegalArgumentException("观测棋盘必须恰有 90 格");
        this.cells = cells.clone();
        for (byte value : this.cells)
            if (value < 0 || value >= LABELS)
                throw new IllegalArgumentException("模型标签无效");
        this.locked = locked == null ? new boolean[Board.SQUARES] : locked.clone();
    }

    /** 该格是否被用户亲手指定过；被指定的格不参与合法化改写。 */
    public boolean isLocked(int square) {
        return locked[square];
    }

    /**
     * 将模型的 [90, 15] logits 直接归约为每格一个标签。
     */
    public static ObservedBoard fromLogits(float[] logits) {
        if (logits == null || logits.length != Board.SQUARES * LABELS)
            throw new IllegalArgumentException("模型输出尺寸无效");
        byte[] cells = new byte[Board.SQUARES];
        for (int square = 0; square < Board.SQUARES; square++) {
            int offset = square * LABELS;
            int best = 0;
            for (int label = 1; label < LABELS; label++)
                if (logits[offset + label] > logits[offset + best])
                    best = label;
            cells[square] = (byte) best;
        }
        return new ObservedBoard(cells);
    }

    public byte at(int square) {
        return cells[square];
    }

    /**
     * 90 格标签的副本，供叠加「暴力校正」等外部修正后重建一张观测棋盘。
     * 取值约定与 {@link #at(int)} 一致：0 空、1..7 红、8..14 黑。
     */
    public byte[] copyCells() {
        return cells.clone();
    }

    /** 当前格的空、红、黑三态，仅供连线比较的热路径使用。 */
    int structureAt(int square) {
        return structure(cells[square]);
    }

    public static ObservedBoard from(Position position) {
        byte[] cells = new byte[Board.SQUARES];
        position.forEachPiece((square, side, type) -> cells[square] = label(side, type));
        return new ObservedBoard(cells);
    }

    /**
     * 将屏幕左上至右下的标签重排为核心固定的红方坐标。
     */
    public ObservedBoard orientForBottom(Side bottomSide) {
        byte[] result = new byte[Board.SQUARES];
        boolean[] resultLocked = new boolean[Board.SQUARES];
        for (int row = 0; row < Board.RANKS; row++)
            for (int column = 0; column < Board.FILES; column++) {
                int targetFile = bottomSide == Side.RED ? column : Board.FILES - 1 - column;
                int targetRank = bottomSide == Side.RED ? Board.RANKS - 1 - row : row;
                int target = Board.square(targetFile, targetRank);
                int source = Board.square(column, row);
                result[target] = cells[source];
                // 用户指定的格也要跟着换坐标，否则翻转后“锁定”会落到别的格上。
                resultLocked[target] = locked[source];
            }
        return new ObservedBoard(result, resultLocked);
    }

    /**
     * 忽略具体兵种，只比较空格、红子、黑子三态。
     */
    public int structureDifference(ObservedBoard other) {
        int difference = 0;
        for (int square = 0; square < Board.SQUARES; square++)
            if (structure(cells[square]) != structure(other.cells[square]))
                difference++;
        return difference;
    }

    /** 只有恰好一个红帅和一个黑将的观测棋盘才能安全重建规则局面。 */
    public boolean canBuildPosition() {
        int redKing = 0;
        int blackKing = 0;
        for (byte label : cells) {
            if (label == 1)
                redKing++;
            else if (label == 8)
                blackKing++;
        }
        return redKing == 1 && blackKing == 1;
    }

    /**
     * 超出规则上限的子力个数，双方合计；0 表示每个兵种的数量都合法。
     *
     * <p>它比 {@link #canBuildPosition()} 严格：将帅齐全但多认出一个炮、一个卒时仍会建盘
     * （{@link #legalized()} 会把它换成数量不足的兵种），但超额本身就是识别偏位的信号，
     * 亚格对齐搜索据此挑出更可信的那一次对齐。</p>
     */
    public int surplusCount() {
        int total = 0;
        for (int offset : new int[]{0, 7}) {
            int[] count = new int[8];
            for (byte label : cells) {
                int kind = label - offset;
                if (kind >= 1 && kind <= 7)
                    count[kind]++;
            }
            for (int kind = 1; kind <= 7; kind++)
                total += Math.max(0, count[kind] - LIMITS[kind]);
        }
        return total;
    }

    /**
     * 把识别结果修正为规则允许的子力数量。
     *
     * <p>识别偶尔会把马读成炮、或凭空多出一个卒，这种局面 Pikafish 会直接报
     * {@code Unsupported position} 并退出进程，连线就永远卡在「等待引擎思考」。
     * 这里把每方超额的子力改成数量不足的兵种：只换兵种、保留占位，所以连线比较看到的
     * 空格/红/黑结构完全不变；只有当该方已凑满 16 子、无处可放时才清空多余格子。</p>
     */
    public ObservedBoard legalized() {
        byte[] result = cells.clone();
        for (int offset : new int[]{0, 7}) {
            int[] count = new int[8];
            for (byte label : result) {
                int kind = label - offset;
                if (kind >= 1 && kind <= 7)
                    count[kind]++;
            }
            int[] deficit = new int[8];
            for (int kind = 1; kind <= 7; kind++)
                deficit[kind] = Math.max(0, LIMITS[kind] - count[kind]);

            List<Integer> surplus = new ArrayList<>();
            for (int kind = 1; kind <= 7; kind++) {
                int extra = count[kind] - LIMITS[kind];
                for (int square = Board.SQUARES - 1; square >= 0 && extra > 0; square--) {
                    if (result[square] - offset != kind)
                        continue;
                    // ★ 用户亲手指定过的子绝不被换走：宁可从别处找同类的超额子，
                    //   也不许把人家刚改好的车又变回卒。
                    if (locked[square])
                        continue;
                    surplus.add(square);
                    extra--;
                }
            }
            for (int square : surplus) {
                int target = largestDeficit(deficit);
                result[square] = (byte) (target == 0 ? EMPTY : target + offset);
                if (target != 0)
                    deficit[target]--;
            }
        }
        applyPositionLimits(result);
        return new ObservedBoard(result, locked);
    }

    /**
     * 把兵种摆回规则允许的位置。
     *
     * <p>识别偶尔会把士、相、帅/将甚至兵卒读偏一两格：占用与颜色都对，看上去“一个萝卜
     * 一个坑”，但 Pikafish 会严格校验士只能在九宫斜线、相/象只能在本方七个田字点、将/帅
     * 只能在九宫内、兵/卒不能退到本方后三线，任一处非法都会报 {@code Unsupported position}
     * 并退出进程。这里把落在非法位置的棋子挪到最近的合法空点；若合法点已被占用，则与不受
     * 位置约束的车/马/炮交换，尽量不改动任何一方的子力数量。</p>
     *
     * <p>调用方传入的都是红方规范坐标（{@link #orientForBottom} 或 {@link #from} 之后），
     * 即红方恒在 rank 0 一侧，因此约束与底边方向无关。</p>
     */
    private static void applyPositionLimits(byte[] cells) {
        // 交换可能把另一枚受约束的棋子挤到非法点，做有限轮固定点消除即可稳定。
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (byte label = 1; label <= 14; label++) {
                int kind = label <= 7 ? label : label - 7;
                if (kind != 1 && kind != 2 && kind != 3 && kind != 7)
                    continue;
                for (int square = 0; square < Board.SQUARES; square++) {
                    if (cells[square] != label || validPosition(label, square))
                        continue;
                    int target = nearestValidSquare(label, square, cells);
                    if (target < 0)
                        continue;
                    byte displaced = cells[target];
                    cells[target] = label;
                    cells[square] = displaced;
                    changed = true;
                }
            }
            if (!changed)
                break;
        }
    }

    /**
     * 距 {@code from} 最近的合法点：优先空点，其次与车/马/炮交换，都没有时返回 -1 保持原样。
     */
    private static int nearestValidSquare(int label, int from, byte[] cells) {
        int bestEmpty = -1;
        int bestEmptyDistance = Integer.MAX_VALUE;
        int bestSwap = -1;
        int bestSwapDistance = Integer.MAX_VALUE;
        for (int candidate = 0; candidate < Board.SQUARES; candidate++) {
            if (!validPosition(label, candidate))
                continue;
            byte occupant = cells[candidate];
            if (occupant == label)
                continue;
            int distance = Math.abs(Board.file(candidate) - Board.file(from))
                + Math.abs(Board.rank(candidate) - Board.rank(from));
            if (occupant == EMPTY) {
                if (distance < bestEmptyDistance) {
                    bestEmptyDistance = distance;
                    bestEmpty = candidate;
                }
            } else if (unconstrainedKind(occupant) && distance < bestSwapDistance) {
                bestSwapDistance = distance;
                bestSwap = candidate;
            }
        }
        return bestEmpty >= 0 ? bestEmpty : bestSwap;
    }

    /** 车、马、炮没有位置限制，可以安全地被交换到任意格。 */
    private static boolean unconstrainedKind(byte label) {
        int kind = label <= 7 ? label : label - 7;
        return kind == 4 || kind == 5 || kind == 6;
    }

    /**
     * 将帅、士、相/象、兵/卒的合法落点，按红方在下（rank 0 为红方底线）归一化后判断。
     */
    private static boolean validPosition(int label, int square) {
        int kind = label <= 7 ? label : label - 7;
        boolean red = label <= 7;
        int file = Board.file(square);
        int rank = red ? Board.rank(square) : Board.RANKS - 1 - Board.rank(square);
        return switch (kind) {
            case 1 -> rank <= 2 && file >= 3 && file <= 5;
            case 2 -> (rank <= 2 && (file == 3 || file == 5)) || (rank == 1 && file == 4);
            case 3 -> (rank == 0 && (file == 2 || file == 6))
                || (rank == 2 && (file == 0 || file == 4 || file == 8))
                || (rank == 4 && (file == 2 || file == 6));
            case 7 -> rank >= 3 && (rank > 3 || file % 2 == 0);
            default -> true;
        };
    }

    /** 还差得最多、最像被认错的那一类；都补齐时返回 0，表示只能清空。 */
    private static int largestDeficit(int[] deficit) {
        int best = 0;
        int most = 0;
        for (int kind = 1; kind <= 7; kind++)
            if (deficit[kind] > most) {
                most = deficit[kind];
                best = kind;
            }
        return best;
    }

    /**
     * 将已按核心坐标排列的观测棋盘用于首次建盘或用户同步。
     */
    public Position toPosition(Side sideToMove) {
        ObservedBoard legal = legalized();
        Board board = new Board();
        for (int square = 0; square < Board.SQUARES; square++) {
            byte label = legal.cells[square];
            if (label == EMPTY)
                continue;
            Side side = label <= 7 ? Side.RED : Side.BLACK;
            board.put(square, pieceType(label <= 7 ? label : label - 7), side == Side.BLACK);
        }
        return Position.fromBoard(board, sideToMove);
    }

    private static int structure(byte label) {
        return label == EMPTY ? 0 : label <= 7 ? 1 : 2;
    }

    private static byte label(Side side, PieceType type) {
        int kind = switch (type) {
            case KING -> 1;
            case ADVISOR -> 2;
            case BISHOP -> 3;
            case KNIGHT -> 4;
            case ROOK -> 5;
            case CANNON -> 6;
            case PAWN -> 7;
        };
        return (byte) (side == Side.RED ? kind : kind + 7);
    }

    private static PieceType pieceType(int label) {
        return switch (label) {
            case 1 -> PieceType.KING;
            case 2 -> PieceType.ADVISOR;
            case 3 -> PieceType.BISHOP;
            case 4 -> PieceType.KNIGHT;
            case 5 -> PieceType.ROOK;
            case 6 -> PieceType.CANNON;
            case 7 -> PieceType.PAWN;
            default -> throw new IllegalStateException("模型标签无效");
        };
    }
}
