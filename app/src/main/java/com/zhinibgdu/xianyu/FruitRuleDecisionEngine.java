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

        /*
         * IMPORTANT: the collector is a vertical LIFO stack. trayTypes is
         * bottom->top. Only the TOP fruit can pair with a newly clicked fruit.
         *
         * Example:
         *   bottom A
         *          B
         *   top    A
         * does NOT auto-clear the two As because B separates them.
         */
        if (trayCount >= 4) {
            return new Decision(
                    Kind.WAIT_TRANSIENT,
                    null,
                    "检测到第4颗叠加，停止追加点击，等待顶部二消或失败结果"
            );
        }

        // Empty stack: choose the most abundant currently exposed type.
        if (trayCount == 0) {
            FruitTemplateMatcher.DetectedFruit target =
                    chooseMostNumerous(clickable, null);
            if (target == null) {
                return deadlock("槽位为空但没有已识别、未遮挡水果可点");
            }
            return click(target, "空槽：点击未遮挡数量最多的水果");
        }

        String top = trayTypes.get(trayTypes.size() - 1);

        // If the TOP TWO are already the same, they are the only pair eligible
        // to auto-clear. Wait for animation; do not add another fruit.
        if (trayCount >= 2) {
            String belowTop = trayTypes.get(trayTypes.size() - 2);
            if (top.equals(belowTop)) {
                return new Decision(
                        Kind.WAIT_TRANSIENT,
                        null,
                        "槽顶两颗同类 " + top + "，等待自动二消"
                );
            }
        }

        // Any non-empty stack first tries to match the CURRENT TOP.
        FruitTemplateMatcher.DetectedFruit topMatch =
                chooseBestOfTypes(clickable, setOf(top));
        if (topMatch != null) {
            return click(
                    topMatch,
                    "栈顶规则：只补当前顶部 " + top + " 形成相邻二消"
            );
        }

        // With one fruit only, there is still one safe staging slot. If the
        // top cannot be matched, push one abundant new type and make it the new
        // top. From two occupied slots onward, introducing anything other than
        // the top type creates A,B,A / A,B,C style non-clearing danger.
        if (trayCount == 1) {
            FruitTemplateMatcher.DetectedFruit target =
                    chooseMostNumerous(clickable, null);
            if (target == null) {
                return deadlock("槽位1个且没有未遮挡水果可点");
            }
            return click(
                    target,
                    "槽位1个且无顶部同类：选择数量最多的新类型作为新栈顶"
            );
        }

        if (trayCount == 2) {
            return deadlock(
                    "槽位已有2颗且当前栈顶 " + top
                            + " 无可点同类；点击下层类型或新类型都不能消除"
            );
        }

        // trayCount == 3. Stack is full; ONLY the current top type is safe.
        return deadlock(
                "槽位已满且当前栈顶 " + top
                        + " 无可点同类；任何其他水果都会形成非相邻堆叠/失败"
        );
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
