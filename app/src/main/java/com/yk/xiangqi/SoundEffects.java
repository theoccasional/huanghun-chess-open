package com.yk.xiangqi;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import com.pikafish.chess.R;
import com.yk.xiangqi.bridge.BoardProjection;
import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;

/**
 * 走子音效：普通走子、吃子各一记，对方被将时再补一记将军音。
 *
 * <p>音频直接取用参考软件《黄昏象棋》的走子音效，触发规则也照它的做法：
 * 先按是否吃子放走子音，再判断落子后对方是否被将而补将军音。</p>
 *
 * <p>注册在应用级 {@link GameRuntime} 上而非 Activity 上，
 * 是为了连线期间即使界面不在前台也能听到对方走子的声音。</p>
 */
public final class SoundEffects implements GameRuntime.Listener {
    private final SoundPool pool;
    private final int moveSound;
    private final int captureSound;
    private final int checkSound;

    public SoundEffects(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build();
        moveSound = pool.load(context, R.raw.move, 1);
        captureSound = pool.load(context, R.raw.capture, 1);
        checkSound = pool.load(context, R.raw.check, 1);
    }

    @Override
    public void onGameChanged(GameState state, GameAction action, GameRuntime.Origin origin) {
        if (!(action instanceof GameAction.Play play))
            return;
        int ply = state.cursor();
        if (ply <= 0)
            return;
        Move move = play.move();
        // 落子前的局面：目标格上是否有子，决定这一记是吃子还是普通走子。
        char[] before = BoardProjection.characters(state.positionAt(ply - 1));
        boolean capture = before[move.to] != '.';
        pool.play(capture ? captureSound : moveSound, 1f, 1f, 1, 0, 1f);
        // 落子后轮到对方走；对方正被将军时，照参考软件再补一记将军音。
        if (state.position().isInCheck())
            pool.play(checkSound, 1f, 1f, 1, 0, 1f);
    }

    public void release() {
        pool.release();
    }
}