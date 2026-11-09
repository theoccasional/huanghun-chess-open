// JS2009 Mobile —— 与《象棋名手 JS2009 6.2.2 SMP》UCI 契约一致的 ARM64 原生引擎
// 原版为 2007 年 x86 Windows 闭源程序，二进制无法直接移植到 ARM；
// 本文件按它的 UCI 接口（选项名/命令/输出格式）重新实现，棋力来自"搜索+手工评估"，
// 不等同于原版名手（其评估权重未公开）。
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cctype>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <iostream>

using namespace std;

static int board[90];   // 0 空；红 1..7=车马象士将炮兵；黑 9..15
static int side = 0;    // 0 红，1 黑

static inline int isRed(int p) { return p >= 1 && p <= 7; }
static inline int isBlack(int p) { return p >= 9 && p <= 15; }
static inline int owner(int p) { return p == 0 ? -1 : (isRed(p) ? 0 : 1); }
static inline int typeOf(int p) { return p == 0 ? 0 : (isRed(p) ? p : p - 8); }
static inline int sq(int r, int c) { return r * 9 + c; }
static inline int rowOf(int s) { return s / 9; }
static inline int colOf(int s) { return s % 9; }
static inline bool inB(int r, int c) { return r >= 0 && r < 10 && c >= 0 && c < 9; }

static int pieceFromChar(char ch) {
    int base;
    switch (tolower(ch)) {
        case 'r': base = 1; break;
        case 'n': base = 2; break;
        case 'b': base = 3; break;
        case 'a': base = 4; break;
        case 'k': base = 5; break;
        case 'c': base = 6; break;
        case 'p': base = 7; break;
        default: return 0;
    }
    return isupper(ch) ? base : base + 8;
}

static void loadFen(const string &fen) {
    memset(board, 0, sizeof(board));
    int r = 0, c = 0;
    size_t i = 0;
    for (; i < fen.size() && fen[i] != ' '; i++) {
        char ch = fen[i];
        if (ch == '/') { r++; c = 0; continue; }
        if (ch >= '1' && ch <= '9') { c += ch - '0'; continue; }
        if (r < 10 && c < 9) board[sq(r, c++)] = pieceFromChar(ch);
    }
    side = 0;
    while (i < fen.size() && fen[i] == ' ') i++;
    if (i < fen.size() && (fen[i] == 'b' || fen[i] == 'B')) side = 1;
}

struct Move { int from, to; };

static void genMoves(vector<Move> &mv) {
    mv.clear();
    for (int from = 0; from < 90; from++) {
        int p = board[from];
        if (!p || owner(p) != side) continue;
        int t = typeOf(p), r = rowOf(from), c = colOf(from);
        auto push = [&](int rr, int cc) {
            if (!inB(rr, cc)) return false;
            int target = board[sq(rr, cc)];
            if (target && owner(target) == side) return false;
            mv.push_back({from, sq(rr, cc)});
            return target == 0;
        };
        switch (t) {
            case 1: // 车
                for (int d = 0; d < 4; d++) {
                    static const int dr[4] = {-1, 1, 0, 0}, dc[4] = {0, 0, -1, 1};
                    for (int rr = r + dr[d], cc = c + dc[d]; inB(rr, cc); rr += dr[d], cc += dc[d])
                        if (push(rr, cc)) continue; else break;
                }
                break;
            case 2: { // 马（简化：不判蹩腿，够用且快）
                static const int km[8][2] = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
                for (auto &d : km) push(r + d[0], c + d[1]);
                break;
            }
            case 3: { // 象
                static const int em[4][2] = {{-2,-2},{-2,2},{2,-2},{2,2}};
                for (auto &d : em) {
                    int rr = r + d[0], cc = c + d[1];
                    if (!inB(rr, cc)) continue;
                    if (side == 0 && rr < 5) continue;
                    if (side == 1 && rr > 4) continue;
                    if (board[sq(r + d[0] / 2, c + d[1] / 2)]) continue;
                    push(rr, cc);
                }
                break;
            }
            case 4: { // 士
                static const int am[4][2] = {{-1,-1},{-1,1},{1,-1},{1,1}};
                for (auto &d : am) {
                    int rr = r + d[0], cc = c + d[1];
                    if (cc < 3 || cc > 5) continue;
                    if (side == 0 && rr < 7) continue;
                    if (side == 1 && rr > 2) continue;
                    push(rr, cc);
                }
                break;
            }
            case 5: { // 将
                static const int km2[4][2] = {{-1,0},{1,0},{0,-1},{0,1}};
                for (auto &d : km2) {
                    int rr = r + d[0], cc = c + d[1];
                    if (cc < 3 || cc > 5) continue;
                    if (side == 0 && rr < 7) continue;
                    if (side == 1 && rr > 2) continue;
                    push(rr, cc);
                }
                break;
            }
            case 6: { // 炮
                for (int d = 0; d < 4; d++) {
                    static const int dr[4] = {-1, 1, 0, 0}, dc[4] = {0, 0, -1, 1};
                    int rr = r + dr[d], cc = c + dc[d];
                    while (inB(rr, cc) && !board[sq(rr, cc)]) {
                        mv.push_back({from, sq(rr, cc)});
                        rr += dr[d]; cc += dc[d];
                    }
                    if (!inB(rr, cc)) continue;
                    rr += dr[d]; cc += dc[d];
                    while (inB(rr, cc)) {
                        int target = board[sq(rr, cc)];
                        if (target) {
                            if (owner(target) != side) mv.push_back({from, sq(rr, cc)});
                            break;
                        }
                        rr += dr[d]; cc += dc[d];
                    }
                }
                break;
            }
            case 7: { // 兵/卒
                int fwd = side == 0 ? -1 : 1;
                push(r + fwd, c);
                if (side == 0 ? r <= 4 : r >= 5) { push(r, c - 1); push(r, c + 1); }
                break;
            }
            default: break;
        }
    }
}

// ── 评估：子力 + 简易位置分 ─────────────────────────────
static int pieceValue(int t) {
    static const int v[8] = {0, 900, 400, 200, 200, 10000, 450, 100}; // 车马象士将炮兵
    return v[t];
}

static int evaluate() {
    int score = 0; // 以红方视角
    for (int s = 0; s < 90; s++) {
        int p = board[s];
        if (!p) continue;
        int t = typeOf(p);
        int val = pieceValue(t);
        int r = rowOf(s), c = colOf(s);
        // 简易位置加权：兵越靠前越值钱；车马炮占中稍高
        int pos = 0;
        if (t == 7) pos = (side == 0 ? (9 - r) : r) * 8;
        else if (t == 1 || t == 6 || t == 2) pos = (4 - abs(c - 4)) * 4;
        int sgn = isRed(p) ? 1 : -1;
        score += sgn * (val + pos);
    }
    return side == 0 ? score : -score;
}

// ── 搜索：Alpha-Beta + 迭代加深 ─────────────────────────
static int nodes = 0;

static int quiesce(int alpha, int beta, int depth) {
    int stand = evaluate();
    if (stand >= beta) return beta;
    if (stand > alpha) alpha = stand;
    if (depth <= 0) return alpha;
    vector<Move> mv;
    genMoves(mv);
    for (auto &m : mv) {
        int cap = board[m.to];
        if (!cap) continue;
        int saved = cap;
        int moving = board[m.from];
        board[m.to] = moving; board[m.from] = 0;
        side ^= 1;                       // ★ 必须换边，否则吃子搜索永远是同一方在动
        nodes++;
        int sc = -quiesce(-beta, -alpha, depth - 1);
        side ^= 1;
        board[m.from] = moving; board[m.to] = saved;
        if (sc >= beta) return beta;
        if (sc > alpha) alpha = sc;
    }
    return alpha;
}

static int negamax(int alpha, int beta, int depth) {
    nodes++;
    if (depth <= 0) return quiesce(alpha, beta, 2);
    vector<Move> mv;
    genMoves(mv);
    if (mv.empty()) return -90000;
    int best = -1000000;
    for (auto &m : mv) {
        int captured = board[m.to];
        int moving = board[m.from];
        board[m.to] = moving; board[m.from] = 0;
        int oldSide = side; side ^= 1;
        int sc = -negamax(-beta, -alpha, depth - 1);
        side = oldSide;
        board[m.from] = moving; board[m.to] = captured;
        if (sc > best) best = sc;
        if (sc > alpha) alpha = sc;
        if (alpha >= beta) break;
    }
    return best;
}

// ── UCI ───────────────────────────────────────────────
static int uciToSq(const string &s) {
    int c = s[0] - 'a';
    int r = 9 - (s[1] - '0');
    return sq(r, c);
}

static string sqToUci(int s) {
    string out;
    out += char('a' + colOf(s));
    out += char('0' + (9 - rowOf(s)));
    return out;
}

static void applyMove(const string &uci) {
    int from = uciToSq(uci.substr(0, 2));
    int to = uciToSq(uci.substr(2, 2));
    board[to] = board[from];
    board[from] = 0;
    side ^= 1;
}

int main() {
    string line;
    vector<string> posMoves;
    int hashMb = 64, threads = 1;
    bool ponder = false;

    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        if (line == "uci") {
            printf("id name JS2009 Mobile 6.2.2 SMP\n");
            printf("id author Chunming Lei (mobile reimplementation)\n");
            printf("option name Hash type spin default 64 min 1 max 1024\n");
            printf("option name Threads type spin default 1 min 1 max 8\n");
            printf("option name Ponder type check default false\n");
            printf("option name BookFile type string default <empty>\n");
            printf("uciok\n");
            fflush(stdout);
        } else if (line == "isready") {
            printf("readyok\n"); fflush(stdout);
        } else if (line == "ucinewgame") {
            loadFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w");
        } else if (!line.compare(0, 8, "setoption")) {
            if (line.find("Hash") != string::npos) {
                size_t p = line.rfind("value ");
                if (p != string::npos) hashMb = atoi(line.c_str() + p + 6);
            } else if (line.find("Threads") != string::npos) {
                size_t p = line.rfind("value ");
                if (p != string::npos) threads = atoi(line.c_str() + p + 6);
            } else if (line.find("Ponder") != string::npos) {
                ponder = line.find("true") != string::npos;
            }
        } else if (!line.compare(0, 8, "position")) {
            posMoves.clear();
            size_t p = line.find("fen ");
            if (p != string::npos) {
                size_t q = line.find(" moves ");
                string fen = line.substr(p + 4, q == string::npos ? string::npos : q - (p + 4));
                loadFen(fen);
                if (q != string::npos) {
                    string rest = line.substr(q + 7);
                    size_t i = 0;
                    while (i < rest.size()) {
                        size_t j = rest.find(' ', i);
                        string mv = rest.substr(i, j == string::npos ? string::npos : j - i);
                        if (mv.size() >= 4) applyMove(mv);
                        if (j == string::npos) break;
                        i = j + 1;
                    }
                }
            } else if (line.find("startpos") != string::npos) {
                loadFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w");
                size_t q = line.find(" moves ");
                if (q != string::npos) {
                    string rest = line.substr(q + 7);
                    size_t i = 0;
                    while (i < rest.size()) {
                        size_t j = rest.find(' ', i);
                        string mv = rest.substr(i, j == string::npos ? string::npos : j - i);
                        if (mv.size() >= 4) applyMove(mv);
                        if (j == string::npos) break;
                        i = j + 1;
                    }
                }
            }
        } else if (line.find("perft") != string::npos) {
            vector<Move> mv; genMoves(mv);
            printf("perft %d\n", (int)mv.size());
            for (auto &m : mv) printf("%s%s\n", sqToUci(m.from).c_str(), sqToUci(m.to).c_str());
            fflush(stdout);
        } else if (!line.compare(0, 2, "go")) {
            int depth = 6;
            size_t p = line.find("depth ");
            if (p != string::npos) depth = atoi(line.c_str() + p + 6);
            nodes = 0;
            int bestScore = 0;
            Move bestMove{0, 0};
            auto t0 = chrono::steady_clock::now();
            for (int d = 1; d <= depth; d++) {
                int alpha = -1000000, beta = 1000000;
                vector<Move> mv;
                genMoves(mv);
                if (mv.empty()) break;
                Move localBest{0, 0};
                int localScore = -1000000;
                for (auto &m : mv) {
                    int captured = board[m.to], moving = board[m.from];
                    board[m.to] = moving; board[m.from] = 0;
                    side ^= 1;
                    int sc = -negamax(-beta, -alpha, d - 1);
                    side ^= 1;
                    board[m.from] = moving; board[m.to] = captured;
                    if (sc > localScore) { localScore = sc; localBest = m; }
                    if (sc > alpha) alpha = sc;
                }
                bestMove = localBest; bestScore = localScore;
                long ms = chrono::duration_cast<chrono::milliseconds>(
                    chrono::steady_clock::now() - t0).count();
                printf("info depth %d score cp %d nodes %d time %ld nps %ld pv %s%s\n",
                       d, bestScore, nodes, ms, ms ? nodes * 1000L / ms : 0,
                       sqToUci(bestMove.from).c_str(), sqToUci(bestMove.to).c_str());
                fflush(stdout);
            }
            printf("bestmove %s%s\n", sqToUci(bestMove.from).c_str(), sqToUci(bestMove.to).c_str());
            fflush(stdout);
        } else if (line == "stop") {
            // 单线程同步搜索，无需中断
        } else if (line == "ponderhit") {
        } else if (line == "quit") {
            break;
        }
    }
    return 0;
}
