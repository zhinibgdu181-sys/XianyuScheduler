package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TaskCategoryRemovalTest {

    @Test
    public void gameModeIntentIsAccepted() {
        assertEquals(TaskCategory.GAME, TaskCategory.fromMode("GAME"));
    }

    @Test
    public void gameTitlesAreRoutedToGameCategory() {
        assertEquals(TaskCategory.GAME, TaskCategory.classify("去消了还想消玩1关"));
        assertEquals(TaskCategory.GAME, TaskCategory.classify("点点消不停"));
        assertEquals(TaskCategory.GAME, TaskCategory.classify("水果小游戏"));
        assertEquals(TaskCategory.GAME, TaskCategory.classify("麻将小游戏"));
    }

    @Test
    public void polishModeIsIndependentCategory() {
        assertEquals(TaskCategory.POLISH, TaskCategory.fromMode("POLISH"));
    }

}
