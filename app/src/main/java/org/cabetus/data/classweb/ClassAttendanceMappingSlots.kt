package org.cabetus.data.classweb

/** 週複数回の科目は既存時間割の一部コマしかなくても、明示確認用の候補にできる。 */
internal object ClassAttendanceMappingSlots {
    fun matches(registered: Set<Pair<Int, Int>>, official: Set<Pair<Int, Int>>): Boolean =
        registered.isNotEmpty() && official.containsAll(registered)
}
