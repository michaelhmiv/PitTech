package com.pittech.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ActivityTimerMigrationTest {
    @Test fun versionEightKeepsRecipesAndIngredientValuesWithoutInventingPrecision(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext; val name = "activity-timer-migration"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open("com.pittech.data.PitTechDatabase/8.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val snapshot = RecipeSnapshotCodec.encode(RecipeSnapshot("Mac", DishDraft("Mac", "Pasta")))
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO cooks(id,title,status,startedAtUtcMillis,startedTimeZoneId,createdAtUtcMillis,updatedAtUtcMillis) VALUES('meal','Meal','active',42,'UTC',42,42)")
            old.execSQL("INSERT INTO dishes(id,cookId,name,foodType,createdAtUtcMillis,updatedAtUtcMillis) VALUES('dish','meal','Mac','Pasta',42,42)")
            old.execSQL("INSERT INTO ingredients(id,cookId,dishId,stage,name,amountValue,amountUnit,createdAtUtcMillis) VALUES('ingredient','meal','dish','main_ingredient','Cheddar',4,'oz',42)")
            old.execSQL("INSERT INTO saved_recipes(id,name,description,tagsJson,currentRevisionId,createdAtUtcMillis,updatedAtUtcMillis,favorite) VALUES('recipe','Mac','','[]','revision',42,42,0)")
            old.execSQL("INSERT INTO recipe_revisions(id,recipeId,revisionNumber,snapshotSchemaVersion,snapshotJson,createdAtUtcMillis,changeNotes) VALUES('revision','recipe',1,1,?,42,'')", arrayOf(snapshot))
            old.version = 8
        } finally { old.close() }
        val room = Room.databaseBuilder(context, PitTechDatabase::class.java, name).addMigrations(PitTechDatabase.MIGRATION_8_9, PitTechDatabase.MIGRATION_9_10, PitTechDatabase.MIGRATION_10_11).build()
        try {
            assertEquals(42L, room.cookDao().getCook("meal")!!.startedAtUtcMillis)
            assertEquals("ingredient", room.cookDao().getAllIngredients().single().id)
            assertEquals(4.0, room.cookDao().getAllIngredients().single().amountValue!!, 0.0)
            assertNull(room.cookDao().getAllIngredients().single().amountText)
            assertEquals(snapshot, room.recipeDao().revision("revision")!!.snapshotJson)
            assertTrue(room.activityTimerDao().all().isEmpty())
            room.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
