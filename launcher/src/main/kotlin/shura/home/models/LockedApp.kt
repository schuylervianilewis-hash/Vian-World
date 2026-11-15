package shura.home.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locked_apps")
data class LockedApp(
    @PrimaryKey val packageName: String,
    val title: String,
    val lockedAt: Long = System.currentTimeMillis()
)
