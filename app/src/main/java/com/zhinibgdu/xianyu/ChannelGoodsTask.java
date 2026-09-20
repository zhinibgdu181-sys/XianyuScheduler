package com.zhinibgdu.xianyu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Counted product visits: a swipe alone never counts as a visit. */
final class ChannelGoodsTask {
    interface Host {
        int remaining();
        boolean openNextProduct();
        boolean returnToList();
        boolean aborted();
        void log(String message);
    }
    static boolean matches(String title) {
        return title != null && title.contains("点击") && title.contains("频道") && title.contains("好物");
    }
    static int remaining(String text) {
        if (text == null) return -1;
        Matcher m = Pattern.compile("再点([0-9]{1,2})个宝贝").matcher(text.replaceAll("\\s+", ""));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
    static boolean productTitle(String text) {
        if (text == null) return false;
        String t = text.replaceAll("\\s+", "");
        // Only the discounted product cards in this channel, never generic claim/buy buttons.
        return t.matches("^[\\[【(（]?抵[0-9]{1,2}[%％][\\]】)）]?.{4,}$")
                && !t.contains("立即购买") && !t.contains("立即领取");
    }
    static boolean run(Host host) {
        if (host.aborted()) return false;
        int previous = host.remaining();
        if (previous < 0 || previous > 20) {
            host.log("未识别到剩余宝贝数量，停止点击并交给任务面板验证");
            return false;
        }
        int stagnant = 0;
        for (int attempt = 0; attempt < 20 && previous > 0; attempt++) {
            if (host.aborted()) return false;
            host.log("还需点击 " + previous + " 个宝贝；准备打开下一件商品");
            if (!host.openNextProduct() || host.aborted() || !host.returnToList()) return false;
            if (host.aborted()) return false;
            int current = host.remaining();
            host.log("返回频道，剩余数量：" + previous + " → " + current);
            if (current < 0) {
                host.log("计数提示消失，不能直接判定成功，交给任务面板验证");
                return false;
            }
            if (current > previous) return false;
            stagnant = current < previous ? 0 : stagnant + 1;
            if (stagnant >= 2) {
                host.log("连续两件商品未增加进度，停止本轮，避免重复空转");
                return false;
            }
            previous = current;
        }
        return previous == 0;
    }
}
