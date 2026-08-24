package com.kmapper.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PACKAGE_NAME = "package_name"
        const val RESULT_APP_LABEL = "app_label"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listView = ListView(this)
        setContentView(listView)

        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName } // don't list KMapper itself
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val labels = apps.map { pm.getApplicationLabel(it).toString() }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        listView.setOnItemClickListener { _, _, position, _ ->
            val app = apps[position]
            setResult(RESULT_OK, Intent().apply {
                putExtra(RESULT_PACKAGE_NAME, app.packageName)
                putExtra(RESULT_APP_LABEL, pm.getApplicationLabel(app).toString())
            })
            finish()
        }
    }
}
