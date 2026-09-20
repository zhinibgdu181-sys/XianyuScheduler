package com.zhinibgdu.xianyu;

/** Coordinates are normalized to the screenshot dimensions, never a device model. */
final class GameTapPolicy {
    private GameTapPolicy() {}
    static boolean allows(int x, int y, int width, int height, String reason) {
        if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) return false;
        double nx = x / (double) width, ny = y / (double) height;
        if ("水果游戏-开始游戏".equals(reason))
            return nx >= 560.0/1440 && nx <= 880.0/1440 && ny >= 2180.0/3120 && ny <= 2500.0/3120;
        if ("水果游戏-死局设置".equals(reason))
            return nx >= .005 && nx <= .065 && ny >= .005 && ny <= .045;
        if ("水果游戏-死局重新开始".equals(reason)
                || "水果游戏-死局确认重开".equals(reason))
            return nx >= .18 && nx <= .82 && ny >= .22 && ny <= .82;
        if ("水果游戏-失败页返回主页".equals(reason))
            return nx >= .20 && nx <= .80 && ny >= .60 && ny <= .90;
        if ("水果游戏-关闭复活弹窗".equals(reason))
            return nx >= .80 && nx <= .93 && ny >= .16 && ny <= .36;
        if ("水果游戏-关闭道具弹窗".equals(reason)
                || (reason != null && reason.startsWith("水果游戏-继续关闭道具弹窗")))
            return nx >= 1160.0/1440 && nx <= 1325.0/1440 && ny >= 740.0/3120 && ny <= 960.0/3120;
        if ("POPUP_CLOSE".equals(reason))
            return nx >= .78 && nx <= .98 && ny >= .02 && ny <= .35;
        if ("POPUP_CLOSE_TOP_RIGHT".equals(reason))
            return nx >= .84 && nx <= .99 && ny >= .02 && ny <= .18;
        // Reward-tool modal ("解锁所有槽位" / "开局消除多组水果") is
        // centered on the game. Only close its own X; never click the green
        // video/"使用" button.
        if ("FRUIT_TOOL_MODAL_CLOSE".equals(reason))
            return nx >= .76 && nx <= .87 && ny >= .17 && ny <= .26;
        if ("FRUIT_UI_CONTINUE".equals(reason)
                || "FRUIT_UI_REVIVE".equals(reason))
            return nx >= .15 && nx <= .85 && ny >= .35 && ny <= .92;
        // Clean fruit solver route actions. Keep them strictly inside the
        // detected board region; do not let a planner turn the whitelist into
        // an unrestricted screen tap path.
        if ("PAIR_FIRST".equals(reason)
                || "PAIR_SECOND".equals(reason)
                || "TRAY_MATCH".equals(reason)
                || "UNLOCK".equals(reason)
                || "水果游戏-V4.87-BOARD_PUSH".equals(reason)
                || "水果游戏-V4.87-TRAY_MATCH".equals(reason)) {
            return ny >= .00 && ny <= .61;
        }
        if ("IDLE_KEEPALIVE".equals(reason)
                || "IDLE_BLANK_SWIPE".equals(reason)) {
            return nx >= .08 && nx <= .92 && ny >= .08 && ny <= .58;
        }
        return false;
    }
}
