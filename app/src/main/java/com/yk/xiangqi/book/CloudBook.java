package com.yk.xiangqi.book;

import com.yk.xiangqi.core.Position;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 云库（开局 + 残局一体）的只读客户端，走 chessdb.cn 网页接口。
 *
 * <p>它只负责把当前局面换成 FEN 请求在线库，并把应答解析成候选着法；不缓存局面、
 * 不持有资源，每次查询都是一次独立 HTTP 请求。请求超时或应答异常时抛异常，由调用方
 * 决定是回退到本地开局库还是纯引擎思考。</p>
 */
public final class CloudBook {
    /** 云库候选着法：着法 UCI、分数（chessdb 的胜负分，越大越优）与胜率（百分比）。 */
    public record Entry(String move, int score, double winRate) {
    }

    private static final String ENDPOINT = "https://www.chessdb.cn/chessdb.php";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    private CloudBook() {
    }

    /**
     * 查询局面在云库里的全部着法，按分数从高到低排列。
     *
     * @throws Exception 网络不可达、超时或应答无法解析时抛出；调用方自行兜底。
     */
    public static List<Entry> query(Position position) throws Exception {
        if (position == null)
            throw new IllegalArgumentException("position");
        String board = URLEncoder.encode(position.toFen(), StandardCharsets.UTF_8.name());
        URL url = new URL(ENDPOINT + "?action=queryall&board=" + board);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "YKXiangqi/0.2");
        connection.setUseCaches(false);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("云库返回 HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return parse(reader.readLine());
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 解析应答：{@code move:uci,score,rank,note,winrate|move:...}。
     * 空应答或未命中时返回空列表。
     */
    static List<Entry> parse(String body) {
        if (body == null || body.isEmpty())
            return Collections.emptyList();
        List<Entry> entries = new ArrayList<>();
        for (String item : body.split("\\|")) {
            int colon = item.indexOf(':');
            if (colon < 0)
                continue;
            String key = item.substring(0, colon).trim();
            if (!"move".equals(key))
                continue;
            String[] fields = item.substring(colon + 1).split(",");
            if (fields.length < 2)
                continue;
            String uci = fields[0].trim();
            if (uci.isEmpty())
                continue;
            try {
                int score = Integer.parseInt(fields[1].trim());
                double winRate = fields.length >= 5 ? Double.parseDouble(fields[4].trim()) : 0d;
                entries.add(new Entry(uci, score, winRate));
            } catch (NumberFormatException ignored) {
                // 单条脏数据不影响其余着法。
            }
        }
        entries.sort((left, right) -> Integer.compare(right.score(), left.score()));
        return entries;
    }
}
