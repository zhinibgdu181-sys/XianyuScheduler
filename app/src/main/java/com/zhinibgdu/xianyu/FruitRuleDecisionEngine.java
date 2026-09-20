package com.zhinibgdu.xianyu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact three-slot decision tree supplied by the user.
 *
 * This engine never invents a "near enough" fruit type. It operates only on
 * template-classified, uncovered fruits.
 */
final class FruitRuleDecisionEngine {
    enum Kind {
        CLICK_FRUIT,
        RESTART_DEADLOCK,
        WAIT_TRANSIENT,
        RESCAN_UNKNOWN
    }

    static final class Decision {
        final Kind kind;
        final FruitTemplateMatcher.DetectedFruit target;
        final String reason;

        Decision(
                Kind kind,
                FruitTemplateMatcher.DetectedFruit target,
                String reason
        ) {
            this.kind = kind;
            this.target = target;
            this.reason = reason == null ? "" : reason;
        }

        String actionKey() {
            if (target == null || target.fruit == null) return "";
            return target.type + ":"
                    + Math.round(target.fruit.centerX / 20.0f) + ":"
                    + Math.round(target.fruit.centerY / 20.0f);
        }
    }

    private FruitRuleDecisionEngine() {}

    static Decision decide(
            FruitTemplateMatcher.State state,
            Set<String> blockedActions
    ) {
        if (state == null) {
            return new Decision(Kind.RESCAN_UNKNOWN, null, "无模板状态");
        }

        Set<String> blocked = blockedActions == null
                ? new HashSet<>() : blockedActions;

        int trayCount = state.stableTrayCount();
        if (trayCount >= 4) {
            return new Decision(
                    Kind.WAIT_TRANSIENT,
                    null,
                    "检测到第4颗瞬时叠加，等待自动消除/失败结果"
            );
        }

        if (state.unknownTrayCount() > 0) {
            return new Decision(
                    Kind.RESCAN_UNKNOWN,
                    null,
                    "槽位存在UNKNOWN_TEMPLATE，禁止冒险点击"
            );
        }

        List<String> trayTypes = new ArrayList<>();
        for (FruitTemplateMatcher.DetectedFruit fruit : state.tray) {
            if (fruit.known()) trayTypes.add(fruit.type);
        }

        List<FruitTemplateMatcher.DetectedFruit> clickable =
                clickableBoard(state, blocked);

        // 状态1：槽位0个。选择当前未遮挡水果中数量最多的一类。
        if (trayCount == 0) {
            FruitTemplateMatcher.DetectedFruit target =
                    chooseMostNumerous(clickable, null);
            if (target == null) {
                return deadlock("槽位为空但没有任何已识别、未遮挡水果可点");
            }
            return click(target, "状态1：槽位0个，点击未遮挡数量最多的水果");
        }

        // 状态1：槽位1个。优先补齐槽内同类；没有才选未遮挡数量最多。
        if (trayCount == 1) {
            String a = trayTypes.get(0);
            FruitTemplateMatcher.DetectedFruit match =
                    chooseBestOfTypes(clickable, setOf(a));
            if (match != null) {
                return click(match, "状态1：槽位1个，优先点击槽内同类 " + a);
            }

            FruitTemplateMatcher.DetectedFruit target =
                    chooseMostNumerous(clickable, null);
            if (target == null) {
                return deadlock("槽位1个且没有任何未遮挡水果可点");
            }
            return click(target, "状态1：无槽内同类，点击未遮挡数量最多的水果");
        }

        if (trayCount == 2) {
            String a = trayTypes.get(0);
            String b = trayTypes.get(1);

            // 状态2：A,A。按用户规则立刻继续点A。
            if (a.equals(b)) {
                FruitTemplateMatcher.DetectedFruit match =
                        chooseBestOfTypes(clickable, setOf(a));
                if (match == null) {
                    return deadlock("状态2：槽位A,A，但场上没有未遮挡A");
                }
                return click(match, "状态2：槽位A,A，立刻点击A");
            }

            // 状态3：A,B。只能点A或B，禁止引入第三种。
            FruitTemplateMatcher.DetectedFruit match =
                    chooseBestOfTypes(clickable, setOf(a, b));
            if (match == null) {
                return deadlock(
                        "状态3：槽位A,B且场上无未遮挡A/B，禁止点击新种类"
                );
            }
            return click(match, "状态3：槽位A,B，只允许点击A或B");
        }

        // trayCount == 3
        Set<String> distinct = new HashSet<>(trayTypes);

        // 二消应该自动清掉重复项；若截到了动画中间态，不再追加点击。
        if (distinct.size() < 3) {
            return new Decision(
                    Kind.WAIT_TRANSIENT,
                    null,
                    "槽位3个但存在重复类型，等待二消动画完成"
            );
        }

        // 状态4：A,B,C。只能点A/B/C；若不存在，立即死局重开。
        FruitTemplateMatcher.DetectedFruit match =
                chooseBestOfTypes(clickable, distinct);
        if (match == null) {
            return deadlock(
                    "状态4：槽位A,B,C已满且场上无未遮挡A/B/C，立即重开"
            );
        }
        return click(match, "状态4：槽位A,B,C已满，只点击已有种类触发消除");
    }

    private static Decision click(
            FruitTemplateMatcher.DetectedFruit fruit,
            String reason
    ) {
        return new Decision(Kind.CLICK_FRUIT, fruit, reason);
    }

    private static Decision deadlock(String reason) {
        return new Decision(Kind.RESTART_DEADLOCK, null, reason);
    }

    private static List<FruitTemplateMatcher.DetectedFruit> clickableBoard(
            FruitTemplateMatcher.State state,
            Set<String> blocked
    ) {
        List<FruitTemplateMatcher.DetectedFruit> result = new ArrayList<>();
        for (FruitTemplateMatcher.DetectedFruit fruit : state.board) {
            if (!fruit.known() || !fruit.uncovered || fruit.fruit == null) continue;
            String key = actionKey(fruit);
            if (blocked.contains(key)) continue;
            result.add(fruit);
        }
        return result;
    }

    private static FruitTemplateMatcher.DetectedFruit chooseMostNumerous(
            List<FruitTemplateMatcher.DetectedFruit> clickable,
            Set<String> allowedTypes
    ) {
        Map<String, Integer> counts = new HashMap<>();
        for (FruitTemplateMatcher.DetectedFruit fruit : clickable) {
            if (allowedTypes != null && !allowedTypes.contains(fruit.type)) continue;
            counts.put(fruit.type, counts.getOrDefault(fruit.type, 0) + 1);
        }

        String bestType = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestType = entry.getKey();
            }
        }
        return bestType == null
                ? null
                : chooseBestOfTypes(clickable, setOf(bestType));
    }

    private static FruitTemplateMatcher.DetectedFruit chooseBestOfTypes(
            List<FruitTemplateMatcher.DetectedFruit> clickable,
            Set<String> allowedTypes
    ) {
        FruitTemplateMatcher.DetectedFruit best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        Map<String, Integer> counts = new HashMap<>();
        for (FruitTemplateMatcher.DetectedFruit fruit : clickable) {
            if (allowedTypes.contains(fruit.type)) {
                counts.put(fruit.type, counts.getOrDefault(fruit.type, 0) + 1);
            }
        }

        for (FruitTemplateMatcher.DetectedFruit fruit : clickable) {
            if (!allowedTypes.contains(fruit.type)) continue;
            int count = counts.getOrDefault(fruit.type, 0);

            // Prefer types that have more currently exposed copies, then cleaner
            // template matches, then a lower fruit on screen.
            double score = 3.0 * count
                    + 2.0 * fruit.confidence
                    + fruit.fruit.centerY / 10000.0;

            if (score > bestScore) {
                bestScore = score;
                best = fruit;
            }
        }
        return best;
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null) set.add(value);
            }
        }
        return set;
    }

    static String actionKey(FruitTemplateMatcher.DetectedFruit fruit) {
        if (fruit == null || fruit.fruit == null) return "";
        return fruit.type + ":"
                + Math.round(fruit.fruit.centerX / 20.0f) + ":"
                + Math.round(fruit.fruit.centerY / 20.0f);
    }
}
