package com.kmapper.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kmapper.app.model.MappingProfile
import java.io.File

class ProfileStorage(private val context: Context) {

    private val gson = Gson()
    private fun dir(): File = File(context.filesDir, "profiles").apply { mkdirs() }

    fun save(profile: MappingProfile) {
        val file = File(dir(), "${profile.profileName}.json")
        file.writeText(gson.toJson(profile))
    }

    fun load(profileName: String): MappingProfile? {
        val file = File(dir(), "$profileName.json")
        if (!file.exists()) return null
        return gson.fromJson(file.readText(), MappingProfile::class.java)
    }

    fun listProfiles(): List<String> =
        dir().listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun delete(profileName: String) {
        File(dir(), "$profileName.json").delete()
    }
}
