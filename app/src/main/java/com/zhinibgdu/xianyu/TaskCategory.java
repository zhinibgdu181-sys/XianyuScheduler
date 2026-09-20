package com.zhinibgdu.xianyu;

/** Task routing is decided before any task button is clicked. */
public enum TaskCategory {
    ALL("自动任务"), LOCAL("闲鱼本地任务"), VIDEO("视频任务"), JUMP("跳转任务");
    public final String label;
    TaskCategory(String label) { this.label = label; }
    public static TaskCategory fromMode(String mode) {
        if (mode == null || "auto".equals(mode)) return ALL;
        // Reject stale pre-4.48.0 game-mode intents.
        if ("GAME".equalsIgnoreCase(mode)) return null;
        try { return valueOf(mode); } catch (IllegalArgumentException e) { return null; }
    }
    /** Null means unknown, external, or unsupported. */
    static TaskCategory classify(String title) {
        if (title == null) return null;
        String n = title.replaceAll("\\s+", "").replace("還想", "还想").replace("點點", "点点").replace("妈蚁", "蚂蚁").replace("访间", "访问")
                .replace("壹", "1").replace("１", "1").replace("I关", "1关").replace("l关", "1关");
        // 先排除明确的外部/跨 App 任务，再判断视频和小游戏。
        // 这些任务虽然显示在闲鱼任务面板，但点击后会离开闲鱼，不属于本地任务。
        if (n.isEmpty() || has(n, "未知", "发布", "下载", "安装", "添加闲鱼币到桌面")) return null;

        if (has(n, "支付宝", "蚂蚁庄园", "农场", "芭芭农场", "头条", "天猫",
                "百亿补贴", "淘宝", "飞猪", "高德", "饿了么", "点淘", "试玩",
                "淘特", "百度", "大众点评", "美团", "快手", "一淘", "逛逛",
                "闪购", "领积分", "赚零花", "短视频")) return JUMP;

        if (has(n, "看15秒视频领奖励", "看视频奖励", "看视频", "观看视频领取奖励",
                "视频领奖励", "视频奖励", "看广告")) return VIDEO;

        // Mini-game automation has been removed. Keep recognizing these
        // titles only to reject them as unsupported instead of routing them.
        if (has(n, "消了还想", "还想消", "点点消", "消不停",
                "水果", "麻将", "小游戏", "玩1关", "玩游戏")) return null;

        // 只把明确属于闲鱼内部任务体系的名称归入 LOCAL。
        if (has(n, "签到", "点击指定频道好物", "指定频道好物",
                "去浏览福利好物", "浏览福利好物", "访问闲鱼币",
                "领取闲鱼币", "闲鱼币", "搜一搜", "搜索", "搜商品")) return LOCAL;

        return null;
    }
    private static boolean has(String text, String... keys) {
        for (String key : keys) if (text.contains(key)) return true;
        return false;
    }
}
