package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CoinRewardParserTest {

    @Test
    public void parsesExplicitCurrencyAfterNumber() {
        assertEquals(30, CoinRewardParser.parseClaimRow(
                "浏览福利好物 +30闲鱼币 领取奖励"));
    }

    @Test
    public void parsesExplicitCurrencyBeforeNumber() {
        assertEquals(50, CoinRewardParser.parseClaimRow(
                "指定频道好物 闲鱼币×50 领取奖励"));
    }

    @Test
    public void parsesSignedRewardInsideVerifiedClaimRow() {
        assertEquals(20, CoinRewardParser.parseClaimRow(
                "搜一搜 +20 领取奖励"));
    }

    @Test
    public void rejectsPercentageBoost() {
        assertEquals(0, CoinRewardParser.parseClaimRow(
                "收益+10% 领取奖励"));
    }

    @Test
    public void rejectsProgressCounterWithoutRewardSignal() {
        assertEquals(0, CoinRewardParser.parseClaimRow(
                "浏览商品 2/3 领取奖励"));
    }
}
