package org.cabetus.domain

/** キャンパス。天気予報の座標と時間割の時限時刻に用いる。 */
enum class Campus(
    val displayName: String,
    val shortCode: String,
    val latitude: Double,
    val longitude: Double,
) {
    KAGURAZAKA("神楽坂", "神", 35.6997, 139.7407),
    KATSUSHIKA("葛飾", "葛", 35.7677, 139.8727),
    NODA("野田", "野", 35.9166, 139.9085);

    companion object {
        fun fromNameOrDefault(name: String?): Campus =
            entries.firstOrNull { it.name == name } ?: KATSUSHIKA
    }
}
