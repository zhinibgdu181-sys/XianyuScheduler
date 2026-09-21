package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelGoodsTaskTest {

    @Test
    public void parsesFinalRemainingItem() {
        assertEquals(1, ChannelGoodsTask.remaining("再点1个宝贝获1个骰子"));
        assertEquals(10, ChannelGoodsTask.remaining("再点10个宝贝获1个骰子"));
    }

    @Test
    public void acceptsOrdinaryProductTitlesNotOnlyDiscountPrefix() {
        assertTrue(ChannelGoodsTask.productTitle("2026最新闲鱼全自动发货工具"));
        assertTrue(ChannelGoodsTask.productTitle("DMA小白避坑科普解答"));
        assertTrue(ChannelGoodsTask.productTitle("【抵30%】三角洲哈弗币收"));
    }

    @Test
    public void rejectsCounterRewardAndChromeRows() {
        assertFalse(ChannelGoodsTask.productTitle("再点1个宝贝获1个骰子"));
        assertFalse(ChannelGoodsTask.productTitle("闲鱼币最大可抵 x0.20"));
        assertFalse(ChannelGoodsTask.productTitle("公告 攻略"));
        assertFalse(ChannelGoodsTask.productTitle("立即购买"));
    }
}
