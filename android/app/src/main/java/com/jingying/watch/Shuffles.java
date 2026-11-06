package com.jingying.watch;

import java.util.List;
import java.util.Random;

/**
 * Fisher–Yates 原地打乱。
 *
 * 随机观看需要的是「整段播放顺序都是随机排列」，而不是只随机一个起点、
 * 然后顺着作者目录连续看下去（那样同一个作者的一堆视频总扎堆在一起）。
 * 这个类不依赖任何 Android API，方便直接单测。
 */
public final class Shuffles {

    private Shuffles() {
    }

    public static <T> void fisherYates(List<T> list, Random random) {
        for (int index = list.size() - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            T tmp = list.get(index);
            list.set(index, list.get(swap));
            list.set(swap, tmp);
        }
    }
}
