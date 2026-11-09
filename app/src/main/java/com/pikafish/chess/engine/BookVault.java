package com.pikafish.chess.engine;

/**
 * ★★★ 全局开局库仓（进程内单例）。★★★
 *
 * <p>原先 {@code ChessGame.localBook} 是每局各建一份，导致「发送端界面加载的开局库」与
 * 「主界面对弈用的开局库」是两套互不相干的东西——用户在发送端加了库，主界面却看不到、
 * 也用不上。这里改成进程内唯一的仓：谁加载都进这一个仓，主界面对弈、发送端界面、
 * 后续的连线模式都读同一份。</p>
 */
public final class BookVault {

    private BookVault() {}

    private static volatile LocalBookManager instance;

    /** 取全局开局库管理器（首次访问时创建）。 */
    public static LocalBookManager manager() {
        LocalBookManager current = instance;
        if (current == null) {
            synchronized (BookVault.class) {
                current = instance;
                if (current == null) {
                    current = new LocalBookManager();
                    instance = current;
                }
            }
        }
        return current;
    }
}
