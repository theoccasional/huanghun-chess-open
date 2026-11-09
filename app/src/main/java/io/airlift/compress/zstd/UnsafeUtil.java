/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.compress.zstd;

import java.nio.Buffer;

/**
 * 纯 Java 版 aircompressor 的「Unsafe 替身」。
 *
 * ── 为什么必须替换 ────────────────────────────────────────────────
 * 原版 aircompressor 通过反射取 {@code sun.misc.Unsafe#theUnsafe}，并读写
 * {@code java.nio.Buffer#address}。在 Android(targetSdk 28+) 上，这两个成员都在
 * 隐藏 API 黑名单里：静态初始化会抛 {@code IncompatibleJvmException}，于是 zstd
 * 解压彻底不可用 —— 真机表现就是「.qyl 读不出来」（而桌面 JVM 单元测试却是好的）。
 *
 * ── 替换策略 ──────────────────────────────────────────────────────
 * 本项目只走 {@code decompress(byte[], ...)} 这条路径，所有 base 都是 {@code byte[]}，
 * 偏移量本质就是「数组下标」。因此这里把 {@code ARRAY_BYTE_BASE_OFFSET} 定为 0，
 * 并用普通数组读写实现那套 {@code getByte/putLong/...} 语义：
 *   · 读越界 → 补 0（原版读的是堆内填充字节，本就不参与最终结果）；
 *   · 写越界 → 只写落在数组内的字节（原版写的是堆内填充字节，同样不参与结果）。
 * 这样既不需要任何 native/隐藏 API，又与原版结果逐字节一致。
 *
 * 注意：直接缓冲区（DirectByteBuffer）走不通这条路，本项目的 QYL 解码不使用它。
 */
final class UnsafeUtil
{
    /** 与 sun.misc.Unsafe 同名常量：数组访存的基准偏移。纯数组实现取 0。 */
    public static final int ARRAY_BYTE_BASE_OFFSET = 0;

    public static final Unsafe UNSAFE = new Unsafe();

    private UnsafeUtil() {}

    /** 直接缓冲区不受支持（原版依赖 sun.misc.Unsafe 读 Buffer.address，Android 上不可用）。 */
    public static long getAddress(Buffer buffer)
    {
        throw new UnsupportedOperationException("direct buffers are not supported by the pure-Java zstd decompressor");
    }

    /**
     * sun.misc.Unsafe 的极小替身：只保留 zstd 解码真正用到的字节级读写，
     * 完全基于 {@code byte[]} 下标，全平台通用。
     */
    public static final class Unsafe
    {
        private static byte at(byte[] a, int i)
        {
            return (i >= 0 && i < a.length) ? a[i] : 0;
        }

        private static void set(byte[] a, int i, byte v)
        {
            if (i >= 0 && i < a.length) {
                a[i] = v;
            }
        }

        public byte getByte(Object base, long offset)
        {
            return at((byte[]) base, (int) offset);
        }

        public short getShort(Object base, long offset)
        {
            byte[] a = (byte[]) base;
            int i = (int) offset;
            return (short) ((at(a, i) & 0xFF) | ((at(a, i + 1) & 0xFF) << 8));
        }

        public int getInt(Object base, long offset)
        {
            byte[] a = (byte[]) base;
            int i = (int) offset;
            return (at(a, i) & 0xFF)
                    | ((at(a, i + 1) & 0xFF) << 8)
                    | ((at(a, i + 2) & 0xFF) << 16)
                    | ((at(a, i + 3) & 0xFF) << 24);
        }

        public long getLong(Object base, long offset)
        {
            byte[] a = (byte[]) base;
            int i = (int) offset;
            long v = 0;
            for (int k = 0; k < 8; k++) {
                v |= (at(a, i + k) & 0xFFL) << (8 * k);
            }
            return v;
        }

        public void putByte(Object base, long offset, byte value)
        {
            set((byte[]) base, (int) offset, value);
        }

        public void putShort(Object base, long offset, short value)
        {
            byte[] a = (byte[]) base;
            int i = (int) offset;
            set(a, i, (byte) value);
            set(a, i + 1, (byte) (value >>> 8));
        }

        public void putInt(Object base, long offset, int value)
        {
            byte[] a = (byte[]) base;
            int i = (int) offset;
            for (int k = 0; k < 4; k++) {
                set(a, i + k, (byte) (value >>> (8 * k)));
            }
        }

        public void putLong(Object base, long offset, long value)
        {
            byte[] a = (byte[]) base;
            int i = (int) offset;
            for (int k = 0; k < 8; k++) {
                set(a, i + k, (byte) (value >>> (8 * k)));
            }
        }

        public void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes)
        {
            if (bytes <= 0) {
                return;
            }
            byte[] src = (byte[]) srcBase;
            byte[] dest = (byte[]) destBase;
            int srcPos = (int) srcOffset;
            int destPos = (int) destOffset;
            int len = (int) bytes;

            // 与 sun.misc.Unsafe.copyMemory 语义对齐：越界部分静默截断，绝不抛异常。
            if (srcPos < 0) {
                destPos += -srcPos;
                len -= -srcPos;
                srcPos = 0;
            }
            if (destPos < 0) {
                srcPos += -destPos;
                len -= -destPos;
                destPos = 0;
            }
            len = Math.min(len, Math.min(src.length - srcPos, dest.length - destPos));
            if (len > 0) {
                System.arraycopy(src, srcPos, dest, destPos, len);
            }
        }
    }
}
