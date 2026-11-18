package shura.home.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import shura.home.models.AppLauncher

@Dao
interface AppLaunchersDao {
    @Query("SELECT * FROM apps")
    fun getAppLaunchers(): List<AppLauncher>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(appLaunchers: List<AppLauncher>)

    @Query("UPDATE apps SET title = :title WHERE package_name = :packageName")
    fun updateAppTitle(title: String, packageName: String)

    @Query("UPDATE apps SET `order` = 0")
    fun resetAllAppOrders()

    @Query("UPDATE apps SET `order` = :order WHERE package_name = :packageName AND activity_name = :activityName")
    fun updateAppOrder(order: Int, packageName: String, activityName: String)

    @Query("DELETE FROM apps WHERE package_name = :packageName")
    fun deleteApp(packageName: String)

    @Query("DELETE FROM apps WHERE id = :id")
    fun deleteById(id: Long)
}
