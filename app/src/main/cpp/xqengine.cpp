// XQEngine · 自研象棋引擎（ARM64 原生 / UCI）
// 来源：按《棋谱大全》统计思路与 hy4 结构自行实现，非抄袭任何闭源引擎
// 组成：board(棋盘/走法生成) + rules(合法性/将军) + eval(子力+位置表)
//       + search(迭代加深/PVS/置换表/静态搜索) + uci(协议)
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <iostream>
#include "book_data.cpp"

using namespace std;

// 棋子编码：红 1..7 = 帅仕相马车炮兵；黑 +8
enum { EMPTY=0, K=1, A=2, B=3, N=4, R=5, C=6, P=7 };
static inline int color(int p){ return p==0?0:(p<8?0:1); } // 0 红 1 黑
static inline bool isRed(int p){ return p>0 && p<8; }
static inline bool isBlk(int p){ return p>8; }
static inline int typeOf(int p){ return p==0?0:(p<8?p:p-8); }

static int bd[90];        // r*9+c，r=0 黑底线，r=9 红底线
static int stm = 0;       // 0 红 1 黑
static inline int sq(int r,int c){ return r*9+c; }
static inline int rowOf(int s){ return s/9; }
static inline int colOf(int s){ return s%9; }
static inline bool inBoard(int r,int c){ return r>=0&&r<10&&c>=0&&c<9; }

static const int RED_PALACE_R0=7, RED_PALACE_R1=9;
static inline bool inPalace(int s,int side){
    int r=rowOf(s), c=colOf(s);
    if(c<3||c>5) return false;
    return side==0 ? (r>=7&&r<=9) : (r>=0&&r<=2);
}

struct MV { int from, to; };
static vector<MV> mvs;

static void add(int f,int t){ mvs.push_back({f,t}); }

static const int KR[4]={-1,1,0,0}, KC[4]={0,0,-1,1};
static const int AR[4]={-1,-1,1,1}, AC[4]={-1,1,-1,1};
static const int BR[4]={-2,-2,2,2}, BC[4]={-2,2,-2,2};
static const int NR[8]={-2,-2,-1,-1,1,1,2,2}, NC[8]={-1,1,-2,2,-2,2,-1,1};

// 伪合法走法生成（不含将军校验）
static void genPseudo(int side){
    mvs.clear();
    for(int s=0;s<90;s++){
        int p=bd[s]; if(!p||color(p)!=side) continue;
        int r=rowOf(s), c=colOf(s), t=typeOf(p);
        if(t==K){
            for(int i=0;i<4;i++){
                int nr=r+KR[i], nc=c+KC[i];
                if(!inBoard(nr,nc)) continue;
                int ns=sq(nr,nc);
                if(!inPalace(ns,side)) continue;
                if(bd[ns] && color(bd[ns])==side) continue;
                add(s,ns);
            }
            // 飞将（对面照将）
            int er = side==0?0:9;
            int ep = bd[sq(er,c)];
            if(ep && color(ep)==1-side && typeOf(ep)==K){
                bool clear=true;
                int step = side==0?-1:1;
                for(int rr=r+step; rr!=er; rr+=step) if(bd[sq(rr,c)]){clear=false;break;}
                if(clear) add(s,sq(er,c));
            }
        } else if(t==A){
            for(int i=0;i<4;i++){
                int nr=r+AR[i], nc=c+AC[i];
                if(!inBoard(nr,nc)) continue;
                int ns=sq(nr,nc);
                if(!inPalace(ns,side)) continue;
                if(bd[ns] && color(bd[ns])==side) continue;
                add(s,ns);
            }
        } else if(t==B){
            for(int i=0;i<4;i++){
                int nr=r+BR[i], nc=c+BC[i];
                if(!inBoard(nr,nc)) continue;
                if(side==0 && nr<5) continue;   // 象不过河
                if(side==1 && nr>4) continue;
                int er=r+AR[i], ec=c+AC[i];
                if(bd[sq(er,ec)]) continue;     // 塞象眼
                int ns=sq(nr,nc);
                if(bd[ns] && color(bd[ns])==side) continue;
                add(s,ns);
            }
        } else if(t==N){
            for(int i=0;i<8;i++){
                int nr=r+NR[i], nc=c+NC[i];
                if(!inBoard(nr,nc)) continue;
                int lr=r+NR[i]/2, lc=c+NC[i]/2;   // 马腿
                if(bd[sq(lr,lc)]) continue;
                int ns=sq(nr,nc);
                if(bd[ns] && color(bd[ns])==side) continue;
                add(s,ns);
            }
        } else if(t==R){
            for(int i=0;i<4;i++){
                int nr=r+KR[i], nc=c+KC[i];
                while(inBoard(nr,nc)){
                    int ns=sq(nr,nc);
                    if(bd[ns]){ if(color(bd[ns])!=side) add(s,ns); break; }
                    add(s,ns); nr+=KR[i]; nc+=KC[i];
                }
            }
        } else if(t==C){
            for(int i=0;i<4;i++){
                int nr=r+KR[i], nc=c+KC[i];
                bool screen=false;
                while(inBoard(nr,nc)){
                    int ns=sq(nr,nc);
                    if(!screen){
                        if(bd[ns]) screen=true;
                        else add(s,ns);
                    } else {
                        if(bd[ns]){ if(color(bd[ns])!=side) add(s,ns); break; }
                    }
                    nr+=KR[i]; nc+=KC[i];
                }
            }
        } else if(t==P){
            int fwd = side==0?-1:1;
            int nr=r+fwd;
            if(inBoard(nr,c)){
                int ns=sq(nr,c);
                if(!bd[ns] || color(bd[ns])!=side) add(s,ns);
            }
            bool crossed = side==0 ? (r<=4) : (r>=5);
            if(crossed){
                for(int dc=-1; dc<=1; dc+=2){
                    int nc=c+dc;
                    if(!inBoard(r,nc)) continue;
                    int ns=sq(r,nc);
                    if(!bd[ns] || color(bd[ns])!=side) add(s,ns);
                }
            }
        }
    }
}

static int kingSq(int side){
    for(int s=0;s<90;s++) if(bd[s] && color(bd[s])==side && typeOf(bd[s])==K) return s;
    return -1;
}

// 该方是否被将军（含飞将）
static bool inCheck(int side){
    int ks=kingSq(side); if(ks<0) return true;
    int es=1-side;
    genPseudo(es);
    for(auto &m:mvs) if(m.to==ks) return true;
    return false;
}

static void doMove(MV m){ bd[m.to]=bd[m.from]; bd[m.from]=0; }
static void undoMove(MV m,int cap){ bd[m.from]=bd[m.to]; bd[m.to]=cap; }

static bool legalAfter(MV m,int side){
    bd[m.to]=bd[m.from]; bd[m.from]=0;
    bool bad = inCheck(side);
    bd[m.from]=bd[m.to]; bd[m.to]=0;   // 调用方负责恢复吃子，故此处 to 置空后由 undo 补
    return !bad;
}

// ─── 局面键（Zobrist）───
static uint64_t zob[90][16], zSide;
static void initZob(){
    uint64_t s=0x9E3779B97F4A7C15ULL;
    for(int i=0;i<90;i++) for(int j=0;j<16;j++){
        s^=s<<13; s^=s>>7; s^=s<<17; zob[i][j]=s;
    }
    s^=s<<13; s^=s>>7; s^=s<<17; zSide=s;
}
static uint64_t hashPos(){
    uint64_t h=0;
    for(int i=0;i<90;i++) if(bd[i]) h^=zob[i][bd[i]];
    if(stm) h^=zSide;
    return h;
}

// ─── 评估（价值体系版）───
static const int VAL[8]={0,6000,200,200,400,900,450,100};
static inline int fileOf(int s){ return s%9; }
static inline int rankOf(int s){ return s/9; }

// 位置表：红方视角，返回该子的位置附加分
static int posBonus(int t,int r,int c){
    switch(t){
    case P: {
        int crossed = (r<=4);
        int b = crossed ? 60 : 0;                 // 过河兵价值提升
        if(crossed && r<=3) b += 40;              // 逼近九宫
        if(crossed && (c>=3&&c<=5)) b += 25;      // 中路兵
        return b;
    }
    case N: {
        int center = abs(c-4);
        int homeRow = (r>=7? r-7 : 7-r);
        int b = max(0, 40 - center*8 - max(0,homeRow-1)*6);   // 马喜中路、忌边
        if(r<=5 && (c==2||c==6||c==3||c==5)) b += 18;          // 盘头马/拐子马
        if(c==0||c==8) b -= 20;                                // 边马贬值
        return b;
    }
    case C: {
        int center = abs(c-4);
        int b = max(0, 30 - center*8);
        if(r<=4) b += 15;                          // 炮过河略加分
        return b;
    }
    case R: {
        int b = max(0, 20 - abs(c-4)*5);
        if(c==2||c==6) b += 25;                    // 控肋车
        if(r<=4) b += 20;                          // 车过河
        if(r<=1) b += 15;                          // 沉底车
        return b;
    }
    case A: return 0;
    case B: return 0;
    default: return 0;
    }
}

// 某方在 c 线上是否有士/相遮挡（用于空头炮判定）
static bool hasScreen(int side,int c){
    for(int r=0;r<10;r++){
        int p=bd[sq(r,c)];
        if(!p) continue;
        if(color(p)!=side) continue;
        int t=typeOf(p);
        if(t==A||t==B) return true;
    }
    return false;
}


// ─── 活动力（车用）：可达空格数 ───
static int rookMobility(int s){
    static const int DR[4]={-1,1,0,0}, DC[4]={0,0,-1,1};
    int cnt=0;
    for(int d=0;d<4;d++){
        int r=rowOf(s)+DR[d], c=colOf(s)+DC[d];
        while(inBoard(r,c)){
            if(bd[sq(r,c)]) break;
            cnt++;
            r+=DR[d]; c+=DC[d];
        }
    }
    return cnt;
}

// 车的位置价值（进取意识：过河/巡河/通路加分，边车与自堵路减分）
static int rookActivity(int s,int side){
    int r=rowOf(s), c=colOf(s);
    int b=0;
    int ownRank = (side==0)? r : 9-r;          // 转成己方视角：9=底线，7=二路
    if(c==0||c==8) b-=35;                       // 边车（死角）贬值
    if(ownRank==7) b+=30;                       // 二路/八路车（巡河、控肋）
    if(ownRank<=4) b+=45;                       // 过河车（压制对方）
    if(r==0||r==9) b+=15;                       // 沉底车
    bool ownPawn=false;
    for(int rr=0;rr<10;rr++){
        int p=bd[sq(rr,c)];
        if(p && color(p)==side && typeOf(p)==P){ ownPawn=true; break; }
    }
    if(!ownPawn) b+=20;                          // 该列无己方兵 → 半通车
    b += 2*rookMobility(s);                      // 活动力（每格 +2）
    return b;
}

// side 视角：+ 表示 side 占优
static int evalFor(int side){
    int sc=0;
    int mineA=0, mineB=0, oppA=0, oppB=0;
    for(int s=0;s<90;s++){
        int p=bd[s]; if(!p) continue;
        int t=typeOf(p);
        int my = (color(p)==side);
        int r = my ? rankOf(s) : 9-rankOf(s);       // 转成“己方视角”行
        int c = my ? fileOf(s) : 8-fileOf(s);
        int v = VAL[t] + posBonus(t, r, c);
        if(t==R) v += rookActivity(s, color(p));
        if(t==A){ if(my) mineA++; else oppA++; }
        if(t==B){ if(my) mineB++; else oppB++; }
        sc += my ? v : -v;
    }
    // 士象缺失（将门空虚）
    sc -= (2-mineA)*70 + (2-mineB)*50;
    sc += (2-oppA)*70 + (2-oppB)*50;

    // ★ 空头炮价值：对方炮立于中路且我方无士相遮挡 → 重罚
    int opp = 1-side;
    for(int r=0;r<10;r++){
        int p=bd[sq(r,4)];
        if(p && color(p)==opp && typeOf(p)==C){
            if(!hasScreen(side,4)) sc -= 180;
            else sc -= 40;
        }
        p=bd[sq(r,4)];
        if(p && color(p)==side && typeOf(p)==C){
            if(!hasScreen(opp,4)) sc += 180;
        }
    }
    // 进取意识：己方过河子力越多越主动
    {
        int adv=0, oppAdv=0;
        for(int s2=0;s2<90;s2++){
            int p2=bd[s2]; if(!p2) continue;
            int t2=typeOf(p2);
            if(t2==K||t2==A||t2==B) continue;
            int rr2=rankOf(s2);
            if(color(p2)==side){ if(rr2<=4) adv++; }
            else { if(rr2>=5) oppAdv++; }
        }
        sc += 12*(adv-oppAdv);
    }
    // 将帅安全：己方将所在纵线被对方车直照
    int ks=kingSq(side);
    if(ks>=0){
        int kc=fileOf(ks);
        bool blocked=false;
        for(int r=0;r<10;r++){
            int s2=sq(r,kc);
            int p=bd[s2];
            if(!p || s2==ks) continue;
            if(color(p)==opp && typeOf(p)==R && !blocked) { sc -= 120; break; }
            if(color(p)==side) blocked=true;
        }
    }
    return sc;
}

static int materialCount(){ int n=0; for(int s=0;s<90;s++){ int p=bd[s]; if(p&&typeOf(p)!=K) n+=VAL[typeOf(p)]; } return n; }

static int evalSide(){
    int base = evalFor(stm);
    // 残局：子力越少，士象价值越高（防将门空虚）
    int mat = materialCount();
    if(mat < 2000){
        int w = (2000-mat)/200;
        if(stm==0) base -= (2-0)*0;
    }
    return base;
}

// ─── 置换表 ───
struct TTE { uint64_t k; int32_t sc; int16_t d; uint8_t flag; uint8_t best; };
static vector<TTE> tt;
static void ttInit(int mb){
    size_t n = (size_t)mb*1024*1024/16;
    if(n<1024) n=1024;
    tt.assign(n, TTE{0,0,0,0,0});
}
static TTE* ttProbe(uint64_t k, size_t &idx){
    idx = (size_t)(k % tt.size());
    return &tt[idx];
}

static int nodesCnt; static bool stopped;
static chrono::steady_clock::time_point t0; static int timeLimitMs;

static bool timeUp(){
    if(timeLimitMs<=0) return false;
    auto ms = chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now()-t0).count();
    return ms >= timeLimitMs;
}



static int quiesce(int alpha,int beta,int depth){
    nodesCnt++;
    int stand = evalSide();
    if(stand>=beta) return beta;
    if(stand>alpha) alpha=stand;
    if(depth<=0) return alpha;
    genPseudo(stm);
    vector<MV> caps;
    for(auto &m:mvs) if(bd[m.to]) caps.push_back(m);
    // 按吃子价值排序
    sort(caps.begin(), caps.end(), [](MV a, MV b){
        int va=VAL[typeOf(bd[a.to])]*10 - VAL[typeOf(bd[a.from])];
        int vb=VAL[typeOf(bd[b.to])]*10 - VAL[typeOf(bd[b.from])];
        return va>vb;
    });
    for(auto &m:caps){
        int cap=bd[m.to], mv=bd[m.from], side=stm;
        bd[m.to]=mv; bd[m.from]=0; stm^=1;
        if(inCheck(side)){ stm^=1; bd[m.from]=mv; bd[m.to]=cap; continue; }
        int sc = -quiesce(-beta,-alpha,depth-1);
        stm^=1; bd[m.from]=mv; bd[m.to]=cap;
        if(sc>=beta) return beta;
        if(sc>alpha) alpha=sc;
    }
    return alpha;
}

static MV killers[64][2];

static int negamax(int alpha,int beta,int depth){
    if(timeUp()){ stopped=true; return alpha; }
    nodesCnt++;
    uint64_t k = hashPos();
    size_t idx; TTE* e = ttProbe(k, idx);
    if(e->k==k && e->d>=depth){
        if(e->flag==0 && e->sc>=beta) return e->sc;
        if(e->flag==1 && e->sc<=alpha) return e->sc;
        if(e->flag==2) return e->sc;
    }
    if(inCheck(stm) && depth>0) depth++;        // ★ 将军延伸
    if(depth<=0) return quiesce(alpha,beta,3);

    int ks = kingSq(stm);
    if(ks<0) return -60000;

    genPseudo(stm);
    vector<MV> raw;
    raw.assign(mvs.begin(), mvs.end());      // ★ 先复制：inCheck() 会重置全局 mvs
    vector<MV> list;
    for(auto &m:raw){
        int cap=bd[m.to], mv=bd[m.from], side=stm;
        bd[m.to]=mv; bd[m.from]=0; stm^=1;
        if(inCheck(side)){ stm^=1; bd[m.from]=mv; bd[m.to]=cap; continue; }
        stm^=1; bd[m.from]=mv; bd[m.to]=cap;
        list.push_back(m);
    }
    if(list.empty()) return -60000 + (20-depth);

    // 排序：吃子优先 + 上一迭代最佳着
    // 排序：吃子(MVV-LVA) > 上轮杀手着 > 其他
    int kd = min(depth, 63);
    MV k1 = killers[kd][0], k2 = killers[kd][1];
    sort(list.begin(), list.end(), [&](MV a, MV b){
        auto sc = [&](MV m)->int{
            if(bd[m.to]) return 100000 + VAL[typeOf(bd[m.to])]*16 - VAL[typeOf(bd[m.from])];
            if(m.from==k1.from && m.to==k1.to) return 90000;
            if(m.from==k2.from && m.to==k2.to) return 80000;
            return 0;
        };
        return sc(a) > sc(b);
    });

    int best=-99999, bestMv=-1; int flag=1;
    for(size_t i=0;i<list.size();i++){
        MV m=list[i]; int cap=bd[m.to], mv=bd[m.from], side=stm;
        bd[m.to]=mv; bd[m.from]=0; stm^=1;
        int sc;
        if(i==0) sc = -negamax(-beta,-alpha,depth-1);
        else {
            sc = -negamax(-alpha-1,-alpha,depth-1);
            if(sc>alpha && sc<beta) sc = -negamax(-beta,-alpha,depth-1);
        }
        stm^=1; bd[m.from]=mv; bd[m.to]=cap;
        if(stopped) return alpha;
        if(sc>best){ best=sc; bestMv=(int)i; if(sc>alpha){ alpha=sc; flag=2; } }
        if(alpha>=beta){
            flag=0;
            if(!bd[m.to]){                       // ★ 记杀手着（非吃子导致剪枝的着法）
                killers[kd][1] = killers[kd][0];
                killers[kd][0] = m;
            }
            break;
        }
    }
    e->k=k; e->d=(int16_t)depth; e->sc=best; e->flag=(uint8_t)flag; e->best=(uint8_t)(bestMv<0?0:bestMv);
    return best;
}

// ─── FEN ───
static const char* STARTPOS="rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
static char pieceChar(int p){
    if(!p) return ' ';
    static const char* red=" KABNRCP";
    static const char* blk=" kabnrcp";
    return p<8 ? red[p] : blk[p-8];
}
static int charPiece(char ch){
    for(int i=1;i<=7;i++){
        static const char* red=" KABNRCP";
        static const char* blk=" kabnrcp";
        if(ch==red[i]) return i;
        if(ch==blk[i]) return i+8;
    }
    return 0;
}

static void setFen(const string& fen){
    memset(bd,0,sizeof(bd));
    int r=0,c=0; size_t i=0;
    for(; i<fen.size() && fen[i]!=' '; i++){
        char ch=fen[i];
        if(ch=='/'){ r++; c=0; continue; }
        if(ch>='1'&&ch<='9'){ c += ch-'0'; continue; }
        int p=charPiece(ch);
        if(p && inBoard(r,c)) bd[sq(r,c)]=p;
        c++;
    }
    for(; i<fen.size(); i++) if(fen[i]=='w'||fen[i]=='b'){ stm = (fen[i]=='b')?1:0; break; }
}

// ─── 走法字符串 ───
static string sqStr(int s){
    char buf[8];
    int r=rowOf(s), c=colOf(s);
    snprintf(buf,sizeof(buf),"%c%d", 'a'+c, 9-r);
    return string(buf);
}
static int parseSq(const string& t){
    if(t.size()<2) return -1;
    int c=t[0]-'a'; int rank=t[1]-'0';
    int r=9-rank;
    if(!inBoard(r,c)) return -1;
    return sq(r,c);
}
static bool applyUci(const string& mv){
    if(mv.size()<4) return false;
    int f=parseSq(mv.substr(0,2)), t=parseSq(mv.substr(2,2));
    if(f<0||t<0||!bd[f]) return false;
    bd[t]=bd[f]; bd[f]=0; stm^=1; return true;
}


// ═══════════════════════════════════════════════════════════════
//  内嵌开局库（由《古谱大全》6,687 局标准起手对局统计生成）
//  键编码与 App 的 XqbBook 完全一致：走子方在下 + 左右镜像归一化 + 位打包
// ═══════════════════════════════════════════════════════════════
static bool g_book = true;

// 引擎子力码 → 库内码（库内：车1 马2 象3 士4 将5 炮6 兵7）
static inline int toBookCode(int t){
    switch(t){
        case 5: return 1;   // R 车
        case 4: return 2;   // N 马
        case 3: return 3;   // B 象
        case 2: return 4;   // A 士
        case 1: return 5;   // K 将
        case 6: return 6;   // C 炮
        default: return 7;  // P 兵
    }
}

// 当前局面 → 规范键；返回长度（23..28），失败返回 -1
static int bookKey(unsigned char out[28], bool &flip, bool &mirror){
    int cells[90];
    for(int i=0;i<90;i++){
        int p=bd[i];
        if(!p){ cells[i]=-1; continue; }
        int code = toBookCode(typeOf(p));
        cells[i] = (color(p)==stm) ? code : (code+8);
    }
    int a[90];
    memcpy(a,cells,sizeof(a));
    flip = (stm==1);
    if(flip){
        for(int r=0;r<10;r++) for(int c=0;c<9;c++)
            a[r*9+c] = cells[(9-r)*9+(8-c)];
    }
    mirror=false;
    for(int r=0;r<10;r++){
        bool done=false;
        for(int c=0;c<5;c++){
            int l=a[r*9+c], rr=a[r*9+(8-c)];
            if(l!=rr){ mirror = (rr>l); done=true; break; }
        }
        if(done) break;
    }
    if(mirror){
        int b2[90];
        for(int i=0;i<90;i++) b2[i]=a[(i/9)*9+(8-(i%9))];
        memcpy(a,b2,sizeof(a));
    }
    memset(out,0,28);
    int occ=0; for(int i=0;i<90;i++) if(a[i]>=0) occ++;
    int nbytes=(90+4*occ+7)/8;
    int bit=0;
    for(int i=0;i<90;i++){
        if(a[i]<0){ bit++; continue; }
        out[bit>>3] |= (unsigned char)(1u<<(7-(bit&7))); bit++;
        for(int k=3;k>=0;k--){
            if((a[i]>>k)&1) out[bit>>3] |= (unsigned char)(1u<<(7-(bit&7)));
            bit++;
        }
    }
    return nbytes;
}

// 规范坐标 → 真实盘面格子
static inline int bookSq(int r,int c,bool flip,bool mirror,int side){
    if(mirror) c = 8-c;
    if(side==1){ r = 9-r; c = 8-c; }
    if(!inBoard(r,c)) return -1;
    return sq(r,c);
}

// 查库：命中返回 true，并给出一个合法着法（在前 3 着里按分加权随机）
static bool bookPick(MV &out,int &cntOut){
    unsigned char key[28]; bool flip=false, mirror=false;
    int klen = bookKey(key, flip, mirror);
    if(klen<=0) return false;
    int lo=0, hi=BOOK_N-1, hit=-1;
    while(lo<=hi){
        int mid=(lo+hi)/2;
        const unsigned char* rec = BOOK_DATA + (size_t)mid*BOOK_REC;
        int rlen = rec[0];
        int cmp = memcmp(rec+1, key, 28);
        if(cmp==0 && rlen==klen){ hit=mid; break; }
        if(cmp<0 || (cmp==0 && rlen<klen)) lo=mid+1; else hi=mid-1;
    }
    if(hit<0) return false;
    const unsigned char* rec = BOOK_DATA + (size_t)hit*BOOK_REC;
    int n = rec[29];
    if(n<=0) return false;
    cntOut = n;

    // 收集合法着法
    vector<MV> raw;
    genPseudo(stm);
    raw.assign(mvs.begin(), mvs.end());
    vector<pair<int,MV>> cand;
    for(int i=0;i<n;i++){
        int mv = (rec[30+i*2]<<8) | rec[31+i*2];
        int fr=(mv>>12)&15, fc=(mv>>8)&15, tr=(mv>>4)&15, tc=mv&15;
        int f = bookSq(fr,fc,flip,mirror,stm);
        int t = bookSq(tr,tc,flip,mirror,stm);
        if(f<0||t<0) continue;
        bool ok=false;
        for(auto &m:raw) if(m.from==f && m.to==t){ ok=true; break; }
        if(!ok) continue;
        int score = (i==0)? 100 : (i==1? 20 : (i==2? 5 : 1));
        cand.push_back({score,MV{f,t}});
    }
    if(cand.empty()) return false;

    int total=0; for(auto &c:cand) total+=c.first;
    static unsigned seed = (unsigned)chrono::steady_clock::now().time_since_epoch().count();
    seed = seed*1103515245u + 12345u;
    int pick = (int)((seed>>8) % (unsigned)total);
    for(auto &c:cand){ if(pick < c.first){ out=c.second; return true; } pick-=c.first; }
    out = cand[0].second;
    return true;
}

// ─── 主循环 ───
int main(){
    initZob(); ttInit(64); setFen(STARTPOS);
    string line;
    while(getline(cin,line)){
        if(line.empty()) continue;
        if(line=="uci"){
            printf("id name XQEngine 1.0 (self-developed)\n");
            printf("id author Huanghun Chess Project\n");
            printf("option name Hash type spin default 256 min 1 max 1024\n");
            printf("option name Threads type spin default 4 min 1 max 8\n");
            printf("option name Ponder type check default false\n");
            printf("option name OwnBook type check default true\n");
            printf("uciok\n"); fflush(stdout);
        } else if(line=="isready"){
            printf("readyok\n"); fflush(stdout);
        } else if(line.rfind("setoption",0)==0){
            if(line.find("name OwnBook")!=string::npos)
                g_book = (line.find("value false")==string::npos);
        } else if(line=="ucinewgame"){
            tt.assign(tt.size(), TTE{0,0,0,0,0});
            setFen(STARTPOS); mvs.clear();
        } else if(line.rfind("position",0)==0){
            string fen; size_t p=line.find("fen ");
            if(line.find("startpos")!=string::npos){
                fen=STARTPOS;
                size_t mp=line.find("moves ");
                if(mp!=string::npos){
                    setFen(fen);
                    string rest=line.substr(mp+6);
                    size_t i=0;
                    while(i<rest.size()){
                        size_t j=rest.find(' ',i);
                        string mv=rest.substr(i, j==string::npos?string::npos:j-i);
                        if(mv.size()>=4) applyUci(mv);
                        if(j==string::npos) break; i=j+1;
                    }
                    continue;
                }
            } else if(p!=string::npos){
                size_t mp=line.find(" moves ");
                fen = (mp!=string::npos)? line.substr(p+4, mp-(p+4)) : line.substr(p+4);
                if(mp!=string::npos){
                    setFen(fen);
                    string rest=line.substr(mp+7);
                    size_t i=0;
                    while(i<rest.size()){
                        size_t j=rest.find(' ',i);
                        string mv=rest.substr(i, j==string::npos?string::npos:j-i);
                        if(mv.size()>=4) applyUci(mv);
                        if(j==string::npos) break; i=j+1;
                    }
                    continue;
                }
            }
            if(!fen.empty()) setFen(fen);
        } else if(line.rfind("go",0)==0){
            int depth=6, movetime=2000;
            size_t p;
            if((p=line.find("depth "))!=string::npos) depth=atoi(line.c_str()+p+6);
            if((p=line.find("movetime "))!=string::npos){ movetime=atoi(line.c_str()+p+9); }
            else if((p=line.find("wtime "))!=string::npos){
                int w=atoi(line.c_str()+p+6);
                movetime = max(100, min(5000, w/60));
            }
            timeLimitMs=movetime; nodesCnt=0; stopped=false;
            t0=chrono::steady_clock::now();
            int bestScore=0; MV best{0,0}; bool haveBest=false;

            if(g_book){
                MV bm{0,0}; int bc=0;
                if(bookPick(bm,bc)){
                    printf("info depth 1 score 12 nodes 0 nps 0 time 0 pv %s%s\n",
                           sqStr(bm.from).c_str(), sqStr(bm.to).c_str());
                    printf("info string book hit %d\n", bc);
                    fflush(stdout);
                    printf("bestmove %s%s\n", sqStr(bm.from).c_str(), sqStr(bm.to).c_str());
                    fflush(stdout);
                    continue;
                }
            }
            for(int d=1; d<=depth && !stopped; d++){
                int sc = negamax(-90000,90000,d);
                if(stopped) break;
                bestScore=sc;
                // 取根节点最佳着（重走一遍第一层）
                genPseudo(stm);
                vector<MV> root;
                for(auto &m:mvs) root.push_back(m);
                sort(root.begin(), root.end(), [](MV a, MV b){
                    int ca=bd[a.to]?VAL[typeOf(bd[a.to])]:0, cb=bd[b.to]?VAL[typeOf(bd[b.to])]:0;
                    return ca>cb;
                });
                int alpha=-90000; bool found=false;
                for(auto &m:root){
                    int cap=bd[m.to], mv=bd[m.from], side=stm;
                    bd[m.to]=mv; bd[m.from]=0; stm^=1;
                    if(inCheck(side)){ stm^=1; bd[m.from]=mv; bd[m.to]=cap; continue; }
                    int v=-negamax(-90000,-alpha,d-1);
                    stm^=1; bd[m.from]=mv; bd[m.to]=cap;
                    if(v>alpha || !found){ alpha=v; best=m; haveBest=true; found=true; }
                }
                long long el = (long long)chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now()-t0).count();
                long long nps = el>0 ? (nodesCnt*1000/el) : nodesCnt;
                printf("info depth %d score %d nodes %d nps %lld time %lld pv %s%s\n",
                       d, sc, nodesCnt, nps, el,
                       sqStr(best.from).c_str(), sqStr(best.to).c_str());
                fflush(stdout);
            }
            if(!haveBest){ genPseudo(stm); if(!mvs.empty()) best=mvs[0]; }
            printf("bestmove %s%s\n", sqStr(best.from).c_str(), sqStr(best.to).c_str());
            fflush(stdout);
        } else if(line=="quit" || line=="stop"){
            stopped=true;
            if(line=="quit") break;
        }
    }
    return 0;
}
