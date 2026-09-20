package com.zhinibgdu.xianyu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative parser for reward values shown in a task row next to
 * "领取奖励". It deliberately rejects percentages/progress counters.
 */
final class CoinRewardParser {
    private static final Pattern NUMBER_BEFORE_CURRENCY = Pattern.compile(
            "(\\d{1,6})\\s*(?:闲鱼币|鱼币)"
    );
    private static final Pattern CURRENCY_BEFORE_NUMBER = Pattern.compile(
            "(?:闲鱼币|鱼币)\\s*[xX×+：:]?\\s*(\\d{1,6})"
    );
    private static final Pattern SIGNED_REWARD = Pattern.compile(
            "\\+\\s*(\\d{1,6})(?!\\s*[%/])"
    );

    private CoinRewardParser() {
    }

    static int parseClaimRow(String raw) {
        if (raw == null) return 0;
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return 0;

        int explicit = firstPositive(NUMBER_BEFORE_CURRENCY, text);
        if (explicit > 0) return explicit;

        explicit = firstPositive(CURRENCY_BEFORE_NUMBER, text);
        if (explicit > 0) return explicit;

        // A signed number is accepted only inside a verified task reward row.
        // Explicitly exclude percentages such as "收益+10%".
        Matcher signed = SIGNED_REWARD.matcher(text);
        while (signed.find()) {
            int end = signed.end();
            int look = end;
            while (look < text.length() && Character.isWhitespace(text.charAt(look))) {
                look++;
            }
            if (look < text.length() && text.charAt(look) == '%') continue;
            try {
                int value = Integer.parseInt(signed.group(1));
                if (value > 0) return value;
            } catch (Throwable ignored) {
            }
        }

        return 0;
    }

    private static int firstPositive(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value > 0) return value;
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }
}
