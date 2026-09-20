package com.zhinibgdu.xianyu;

import java.util.Base64;

/**
 * Initial fruit templates sampled from the user's real 13738.jpg game frame.
 *
 * Each template is a 7x7 RGB appearance grid using the exact sampling format
 * produced by FruitVisionEngine.Fruit.visualGrid. Unknown/new fruits are never
 * coerced into the closest known type; FruitTemplateMatcher applies distance
 * and ambiguity thresholds before assigning a type.
 */
final class FruitTemplateCatalog {
    static final String UNKNOWN = "UNKNOWN_TEMPLATE";

    static final String[] IDS = {
            "FRUIT_01_BROWN",
            "FRUIT_02_BLUEBERRY",
            "FRUIT_03_TOMATO",
            "FRUIT_04_YELLOW_RED",
            "FRUIT_05_DARK_RED",
            "FRUIT_06_STRAWBERRY",
            "FRUIT_07_DRAGONFRUIT",
            "FRUIT_08_PEACH",
            "FRUIT_09_WATERMELON",
            "FRUIT_10_BANANA",
            "FRUIT_11_GRAPE",
            "FRUIT_12_LEMON",
            "FRUIT_13_ORANGE",
            "FRUIT_14_DURIAN",
            "FRUIT_15_PURPLE_ONION"
    };

    private static final String[] DATA = {
            "FBofFBofFBofnXJIkWhIFBofFBofFBofmXZQvZllz7eTonxPj2Y6FBofFBofkmw9ooFUo4RVmnFDhFgzaUorclVDhF0yj2U7kmc8gFkucU8qWzkeW0EwbUosdFErdVIsbkwnaEUlYDwi+NfEXT0kYT8jZEImXT4hVTUcFBof431XFBofVzQeVjIcWjckFBofFBof",
            "FBofFBofxtT3FydbFBofFBofFBofFBofFBofFBofbXbDZXfNSl69FBofYIa1FBofsb3tFBofUGnFQFK0FBofLUWBUGnGXnzSU2zJPE+uNUWkFBofFBofTWrISmG/Q1a1OkqqWmTFP1WRFBofFBofSVKvPEmlQFCvRE+rFBofkoYybF4dcWgjFBofFBofFBofFBof",
            "FBofFBofb5FHaIo0XnIqoXRfFBoflmFdmz8maF8ezVMu1GA56ohXFBofjy0gnDUmwEIr21gs7IVY45Fi22o+nEAxjioarTUkyUQl1VIo2Vks1HBOFBofiCYbjioaqTQgtTgixT8kFBofFBofi5yujzEnkSojnDwsFBofFBofFBofFBofFBofFBofFBofFBofFBof",
            "9sFl8Mli88tr7cBj26FN2ZZSFBofuZBY8bdT6LdO4qpL26JJ2ZtOFBofFBofuY1O7ZhI0YE4vXw6FBofFBofFBofFBof2peOzlgq9sGiFBofFBofFBofxEYv229AzVYs675Z1aqKFBofioBn1lYl2XA286dR57talUwZrXVk3LV05Y5B9Mhp9clq57lh26tZuGBK",
            "FBofFBofFBofFBofFBofFBof34JhFBofrXhwcx4bhVtlVRcYFBof12RFFBofmFJIkEpAWBARVQ8NFBofy1c+fD86axgUZhcTWRIQVxUXXhoZnUIwFBofZxwXZRUUXRcXWBobUyUoFBofFBofaT00XiEmWB0hUhwcFBofFBofFBofFBofFBofXUo7FBofFBofFBof",
            "QTSDSTaQFBofFBofFBofFBofyVYnGxZWFBofFBofmahjhq58FBof66NXFBoftY2N22hJ2HBLcp9MorqIu6VsFBof22tG32ZH3WhHxVAvdSgMh62EFBof3WVDzlk4wEQqtj0qu1pJFBofFBofyk4svEUvskMwtEI3vXF1FBofFBofmEUzsD8tr0o+w2hlFBofFBof",
            "FBofFBofirV/kFycwKmHFBofFBofFBofk1aRlECOxEGH0WeLyFp9FBofdJayZDI7FBoftj1zzJmE55ywFBofWm18di1Ksjd4y1B86a7A64qrFBofFBoffSlDtU1oqYBk1Fp/r25oFBofFBofFBof59ifyVxz466gFBofFBofFBofFBofFBofFBofFBofFBofFBof",
            "FBofFBofV0sZFBofFBofFBofFBofFBofFBofFBof5cum+OPOFBofOliOFBof7sOY8cSL8Lh977uT65t4cYPF7aaQ8LSA6a526qBv5ZFl5IBeyo+L5KGG5ZZr5JNk5IJb4Xlc75Z27JBpFBof4Its3npY13Jgu1tC351t34+EFBof4pd34H1d23NY1XBU2390FBof",
            "FBofFBofeZ9UdqY5p8txW4iDFBofFBofgrhGbaI23u+pd6orb6QwFBofRXVNN2QhodpLsORlquBUkslSUoQtN2Q7SHwqeK1Dg7lBh74/bqY7OF8cFBofM2IeHUgST4UtIk4PT4UveqaXFBofQW9VNmAhOGIjKVAbR3dDFBofFBofFBofFBofFBofFBofFBofFBof",
            "FBofFBofFBofFBofbo6dFBofFBofvokt8dhk6OCh69t8blEnSicTFBof9dRh89Zu+tp39eujqYInhnkzFBofuHYsw5I1ypc83rdc8uCKsoE0FBofd1Q29t148dVa8dJez6g/rYE2FBofFBoftYIzwpU6tYY2w5pIbkoolXtYFBofVUQy8Ndj8Ndy5tl94cRet7FZ",
            "FBofFBofZYxxgZ95FBofZZJxFBofFBofcm2je4FbjqxSSmYbQXBgFBofFBoff161FBofg6FLco5BFBofFBofFBofy63jFBofFBofFBofUkGHjbakFBofVj+ZNip2PDJ5HxVSblWxcoOxOEF61r7/Zk2pbE6wHhhWNTF9FBofFBofSjGFIxpdSD2JNzF7FBofFBof",
            "FBof69hx9dNY8NJW7cpSFBofFBof8NFU8dRc9+SY8c9T8MxRsJdhFBof7ctQ8c5Q8M1P7spQ7cRO475UFBof575K6MFM6sFL6cBK3rND1KRCFBof06U22a5I2a1C2a1A3K07FBofFBofx55CzqVL2adE16dFiVw7pHtbFBofFBofFBofFBof4o5q2mdKy1Q2uVA6",
            "FBofFBofoqR1tmB57tKiFBofFBofFBofFBofvJp/1X47xO2NFBofFBofFBofzoyA5YA46Jc+7qVG7KlO4ryXFBofyXVZ3nc15pA7865g88SM6aZLFBof1G9D02wx5IY86Zg/6qFF6qhIFBofz3dF1XA433k354w96JZA6aFVFBofsuPg02wx2XA54nY45oNCFBof",
            "X5gtR3FNFBofFBofTV66QE6vO0urPGkmFBofFBofk4VYFBofJjqDJzF4FBofta1arqJMj4MteWwmhHQ5FBofFBofsqhG5duTv7Ndinw1VUkXFBofjYQ/ta5DzMVqjoA5ppdUS0ATaFkup6N2joY1qJ86ppdUS0ATaFkup6N2joY1qJ86lodGeGkuUkIeZ186FBoffGkvV0YQYFEaemo2WU0dFBof".substring(0,196),
            "8ahMFBofFBofunSmFBofFBofwLVz66dU1pzE05nBsHCijlGKakOSFBofyJS74rzR5rzSwYO2lViTZTx+Z12iunuouH2tqW6eklaMdTt1ZUCFfmGzpWSah0qDgEV7cjlwVCZXWSpgimCiFBoffkR+aDRlVCZUVytcZUBzFBofmGRXFBofFBofWCpRFBofFBofFBof"
    };

    static final float[][] GRIDS = decode();

    private FruitTemplateCatalog() {}

    private static float[][] decode() {
        float[][] result = new float[DATA.length][];
        for (int i = 0; i < DATA.length; i++) {
            byte[] raw = Base64.getDecoder().decode(DATA[i]);
            float[] grid = new float[raw.length];
            for (int j = 0; j < raw.length; j++) {
                grid[j] = (raw[j] & 0xff) / 255.0f;
            }
            result[i] = grid;
        }
        return result;
    }
}
