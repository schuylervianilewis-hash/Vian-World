package shura.home.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import shura.home.models.LockedApp

@Dao
interface LockedAppsDao {
    @Query("SELECT * FROM locked_apps ORDER BY title ASC")
    fun getAllLockedApps(): List<LockedApp>

    @Query("SELECT * FROM locked_apps ORDER BY title ASC")
    fun getAllLockedAppsFlow(): Flow<List<LockedApp>>

    @Query("SELECT packageName FROM locked_apps")
    fun getAllLockedPackages(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :packageName)")
    fun isAppLocked(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLockedApp(app: LockedApp)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    fun deleteLockedApp(packageName: String)

    @Query("DELETE FROM locked_apps")
    fun deleteAllLockedApps()
}
