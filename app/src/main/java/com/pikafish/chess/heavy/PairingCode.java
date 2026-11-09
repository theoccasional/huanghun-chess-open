package com.pikafish.chess.heavy;

import java.security.SecureRandom;

/**
 * ★★★ 配对码：辎重包与操作包「对上就成」的唯一凭据。★★★
 *
 * <p>辎重包端（电脑上的 EXE、另一台手机上的 APK）打开即随机生成一个 6 位数字码并显示；
 * 操作包端把这个码填进来，局域网内会自动找到出示同一码的那台辎重包主机，随即同步。
 * 用户只需「看码、抄码」，不涉及任何地址、端口、文件路径。</p>
 */
public final class PairingCode {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * ★★★ 保留配对码：六位全 0。★★★
     *
     * <p>它不是真实配对码，而是「导入本机辎重包 ZIP」这条旁路的专用口令——操作包端只有
     * 填了这个码，界面才会浮现「导入本机辎重包 ZIP」按钮。{@link #generate()} 首位恒非 0
     * （取值 100000~999999），因此任何辎重包主机出示的码都绝不可能等于本保留码，
     * 二者永不冲突，做到"一码归一码"。</p>
     */
    public static final String LOCAL_IMPORT_CODE = "000000";

    private PairingCode() {}

    /** 生成一个 6 位数字配对码（首位非 0，便于一眼读全；因此永不为 000000）。 */
    public static String generate() {
        int value = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(value);
    }

    /** 是否是「导入本机辎重包 ZIP」这条旁路的保留码（六位全 0）。 */
    public static boolean isLocalImportCode(String code) {
        return LOCAL_IMPORT_CODE.equals(code);
    }

    /** 是否是合法的 6 位数字码。 */
    public static boolean valid(String code) {
        return code != null && code.length() == 6 && code.matches("\\d{6}");
    }

    /** 去掉空白，只留数字（容忍用户抄写时空格/连字符）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length() && digits.length() < 6; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /** 展示用的分组写法：123456 → 123 456（更易读、更易抄）。 */
    public static String spaced(String code) {
        if (code == null || code.length() != 6) {
            return code == null ? "" : code;
        }
        return code.substring(0, 3) + " " + code.substring(3);
    }
}
