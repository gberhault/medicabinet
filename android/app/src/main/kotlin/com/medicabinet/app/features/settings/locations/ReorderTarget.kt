package com.medicabinet.app.features.settings.locations

/**
 * Where a dragged row lands: [from] moved by [offset] pixels over rows of [heights] (in list order). A row is passed
 * once the drag covers at least half of its height; the result stays inside the list. The direction is the sign of
 * [offset]: the leftover after passing a tall row never turns the drag around.
 */
internal fun reorderTarget(from: Int, offset: Float, heights: List<Int>): Int {
    var target = from
    var remaining = offset
    if (offset > 0) {
        while (target < heights.lastIndex && remaining >= heights[target + 1] / 2f) {
            remaining -= heights[target + 1]
            target++
        }
    } else {
        while (target > 0 && -remaining >= heights[target - 1] / 2f) {
            remaining += heights[target - 1]
            target--
        }
    }
    return target
}
