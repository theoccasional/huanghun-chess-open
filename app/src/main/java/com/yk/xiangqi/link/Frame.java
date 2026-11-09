package com.yk.xiangqi.link;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 识别输入的一帧像素：把两种来源统一成同一种「RGBA 字节 + 行步长/像素步长」视图。
 *
 * <p>连线原本只有一条取帧来源——录屏虚拟屏（{@link ImageReader} 产出的 {@link Image}）。
 * 弱点在于虚拟屏<b>只在画面变化时产帧</b>：画面一静止，点「同步/校准」就要半天才有画面，
 * 表现为“点了没反应，非得先去棋盘上点一下”。</p>
 *
 * <p>无障碍服务在线时可以用 {@code AccessibilityService.takeScreenshot()} <b>按需</b>截一张
 * 当前屏幕（{@link Bitmap}），完全不依赖合成器产帧——这正是竞品那条“无脑秒跟”的路子。
 * 两种来源的像素布局不同（Image 是带行/像素步长的 plane 缓冲；Bitmap 是 ARGB 整型数组），
 * 这里统一成同一种 RGBA 视图，识别与检测层只认 {@link Frame}，两条来源因此可互换。</p>
 *
 * <p><b>通道次序：</b>投屏 RGBA_8888 的平面字节是 R,G,B,A；ARGB_8888 位图经
 * {@code getPixels} 拿到的是 0xAARRGGBB 整型，这里按 R,G,B,A 逐字节写回，保证两条来源的
 * 通道次序一致——否则红蓝会调换，识别会整体认错。</p>
 */
public final class Frame implements AutoCloseable {
    private final int width;
    private final int height;
    private final int rowStride;
    private final int pixelStride;
    private final ByteBuffer buffer;
    /** 非空表示本帧拥有该 Bitmap，{@link #close()} 时回收。 */
    private final Bitmap owner;
    /** 非空表示本帧拥有该投屏 {@link Image}，{@link #close()} 时关闭。借用帧两者皆为空。 */
    private final Image ownerImage;

    private Frame(int width, int height, int rowStride, int pixelStride, ByteBuffer buffer,
                 Bitmap owner, Image ownerImage) {
        this.width = width;
        this.height = height;
        this.rowStride = rowStride;
        this.pixelStride = pixelStride;
        this.buffer = buffer;
        this.owner = owner;
        this.ownerImage = ownerImage;
    }

    /** 包一层投屏 {@link Image}；所有权仍归调用方，{@link #close()} 不关闭该 Image。 */
    public static Frame of(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        return new Frame(image.getWidth(), image.getHeight(),
            plane.getRowStride(), plane.getPixelStride(), plane.getBuffer(), null, null);
    }

    /**
     * 包一层投屏 {@link Image} 并<b>接管所有权</b>：本帧存活期间该 Image 一直有效，
     * {@link #close()} 时关闭它。用于把「等待稳定」的一帧暂存起来、稍后再识别的场景
     * （此时调用方已无法自行 close，必须靠本帧代为释放）。
     */
    public static Frame owning(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        return new Frame(image.getWidth(), image.getHeight(),
            plane.getRowStride(), plane.getPixelStride(), plane.getBuffer(), null, image);
    }

    /**
     * 包一层无障碍截图的 {@link Bitmap}（须为软件位图，如 ARGB_8888）。
     * 本帧拥有该 Bitmap，{@link #close()} 时回收，避免每帧几十 MB 的位图堆积。
     */
    public static Frame of(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF));
            buffer.put((byte) ((pixel >> 8) & 0xFF));
            buffer.put((byte) (pixel & 0xFF));
            buffer.put((byte) ((pixel >>> 24) & 0xFF));
        }
        buffer.rewind();
        return new Frame(width, height, width * 4, 4, buffer, bitmap, null);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int rowStride() {
        return rowStride;
    }

    public int pixelStride() {
        return pixelStride;
    }

    public ByteBuffer buffer() {
        return buffer;
    }

    /**
     * 把这一帧还原成 ARGB_8888 位图。检测层（YOLO 定位 / 神经角点 / 梳齿投影）与 ONNX
     * 摆子识别都要吃位图，统一从这里取，两条取帧来源便都能供检测使用。
     *
     * <p>与 {@link #of(Bitmap)} 的写法一致：按 R,G,B,A 逐字节读回再组装成 ARGB 整型，
     * 通道次序与主识别路径（{@link PieceRecognizer}/{@link LayoutRecognizer}）完全一致。
     * 行步长大于可见宽度时（投屏 plane 常有行尾填充），位图按 {@code rowStride/pixelStride}
     * 作行距、可见宽度仍取 {@code width}，避免框坐标整体错位。</p>
     *
     * <p>调用方负责回收返回的位图。</p>
     */
    public Bitmap toBitmap() {
        int bitmapWidth = pixelStride > 0 ? rowStride / pixelStride : width;
        if (bitmapWidth < width)
            bitmapWidth = width;
        int[] pixels = new int[bitmapWidth * height];
        for (int y = 0; y < height; y++) {
            int rowBase = y * rowStride;
            int outBase = y * bitmapWidth;
            for (int x = 0; x < width; x++) {
                int at = rowBase + x * pixelStride;
                int red = buffer.get(at) & 0xFF;
                int green = buffer.get(at + 1) & 0xFF;
                int blue = buffer.get(at + 2) & 0xFF;
                pixels[outBase + x] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return Bitmap.createBitmap(pixels, 0, bitmapWidth, width, height, Bitmap.Config.ARGB_8888);
    }

    @Override
    public void close() {
        if (owner != null && !owner.isRecycled())
            owner.recycle();
        if (ownerImage != null)
            ownerImage.close();
    }
}
