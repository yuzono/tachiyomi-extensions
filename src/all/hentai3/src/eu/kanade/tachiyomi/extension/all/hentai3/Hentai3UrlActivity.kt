package eu.kanade.tachiyomi.extension.all.hentai3

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://3hentai.net/d/xxxxxx intents and redirects them to
 * the main Tachiyomi process.
 */
class Hentai3UrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.SEARCH"
            putExtra("query", intent.data.toString())
            putExtra("filter", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(mainIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e("Hentai3UrlActivity", "Activity not found: " + e.message)
        } catch (e: Throwable) {
            Log.e("Hentai3UrlActivity", "Unexpected throwable: " + e.message)
        }

        finish()
        exitProcess(0)
    }
}
