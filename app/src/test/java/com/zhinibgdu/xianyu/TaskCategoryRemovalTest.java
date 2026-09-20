package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertNull;

public class TaskCategoryRemovalTest {

    @Test
    public void oldGameModeIntentIsRejected() {
        assertNull(TaskCategory.fromMode("GAME"));
    }

    @Test
    public void gameTitlesAreUnsupported() {
        assertNull(TaskCategory.classify("去消了还想消玩1关"));
        assertNull(TaskCategory.classify("点点消不停"));
        assertNull(TaskCategory.classify("水果小游戏"));
        assertNull(TaskCategory.classify("麻将小游戏"));
    }
}
