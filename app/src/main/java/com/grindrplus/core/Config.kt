package com.grindrplus.core

import android.content.Context
import com.grindrplus.GrindrPlus
import com.grindrplus.manager.utils.AppCloneUtils
import org.json.JSONObject
import java.io.IOException

object Config {
    private var localConfig = JSONObject()
    private var currentPackageName = Constants.GRINDR_PACKAGE_NAME
    private val GLOBAL_SETTINGS = listOf("analytics", "discreet_icon", "material_you", "debug_mode")

    fun initialize(packageName: String? = null) {
        Logger.d("Called initialize for package: $packageName", LogSource.MANAGER)

        localConfig = readRemoteConfig()

        if (packageName != null) {
            currentPackageName = packageName
        }

        migrateToMultiCloneFormat()
    }

    private fun isGlobalSetting(name: String): Boolean {
        return name in GLOBAL_SETTINGS
    }

    private fun migrateToMultiCloneFormat() {
        if (!localConfig.has("clones")) {
            val cloneSettings = JSONObject()

            if (localConfig.has("hooks")) {
                val defaultPackageConfig = JSONObject()
                defaultPackageConfig.put("hooks", localConfig.get("hooks"))
                cloneSettings.put(Constants.GRINDR_PACKAGE_NAME, defaultPackageConfig)

                val keysToMove = mutableListOf<String>()
                val keys = localConfig.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "hooks" && !isGlobalSetting(key)) {
                        defaultPackageConfig.put(key, localConfig.get(key))
                        keysToMove.add(key)
                    }
                }
                keysToMove.forEach { localConfig.remove(it) }
            } else {
                cloneSettings.put(Constants.GRINDR_PACKAGE_NAME, JSONObject().put("hooks", JSONObject()))
            }

            localConfig.put("clones", cloneSettings)
            writeRemoteConfig(localConfig)
        }

        ensurePackageExists(currentPackageName)
    }

    fun setCurrentPackage(packageName: String) {
        currentPackageName = packageName
        ensurePackageExists(packageName)
    }

    fun getCurrentPackage(): String {
        return currentPackageName
    }

    private fun ensurePackageExists(packageName: String) {
        val clones = localConfig.optJSONObject("clones") ?: JSONObject().also {
            localConfig.put("clones", it)
        }

        if (!clones.has(packageName)) {
            clones.put(packageName, JSONObject().put("hooks", JSONObject()))
            writeRemoteConfig(localConfig)
        }
    }

    fun getAvailablePackages(context: Context): List<String> {
        val installedClones = listOf(Constants.GRINDR_PACKAGE_NAME) + AppCloneUtils.getExistingClones(context)
        val clones = localConfig.optJSONObject("clones") ?: return listOf(Constants.GRINDR_PACKAGE_NAME)

        return installedClones.filter { pkg ->
            clones.has(pkg)
        }
    }

    fun readRemoteConfig(): JSONObject {
        return try {
            GrindrPlus.bridgeClient.getConfig()
        } catch (e: Exception) {
            Logger.e("Failed to read config file: ${e.message}", LogSource.MANAGER)
            Logger.writeRaw(e.stackTraceToString())
            JSONObject().put("clones", JSONObject().put(
                Constants.GRINDR_PACKAGE_NAME,
                JSONObject().put("hooks", JSONObject()))
            )
        }
    }

    fun writeRemoteConfig(json: JSONObject) {
        try {
            GrindrPlus.bridgeClient.setConfig(json)
        } catch (e: IOException) {
            Logger.e("Failed to write config file: ${e.message}", LogSource.MANAGER)
            Logger.writeRaw(e.stackTraceToString())
        }
    }

    private fun getCurrentPackageConfig(): JSONObject {
        val clones = localConfig.optJSONObject("clones")
            ?: JSONObject().also { localConfig.put("clones", it) }

        return clones.optJSONObject(currentPackageName)
            ?: JSONObject().also { clones.put(currentPackageName, it) }
    }

    fun put(name: String, value: Any) {
        if (isGlobalSetting(name)) {
            localConfig.put(name, value)
        } else {
            val packageConfig = getCurrentPackageConfig()
            packageConfig.put(name, value)
        }

        writeRemoteConfig(localConfig)
    }

    fun get(name: String, default: Any, autoPut: Boolean = false): Any {
        if (isGlobalSetting(name)) {
            val get = localConfig.opt(name)
            return get ?: default.also {
                if (autoPut) put(name, default)
            }
        }

        val packageConfig = getCurrentPackageConfig()
        val get = packageConfig.opt(name)
        return get ?: default.also {
            if (autoPut) put(name, default)
        }
    }

    fun setHookEnabled(hookName: String, enabled: Boolean) {
        val packageConfig = getCurrentPackageConfig()
        val hooks = packageConfig.optJSONObject("hooks")
            ?: JSONObject().also { packageConfig.put("hooks", it) }

        hooks.optJSONObject(hookName)?.put("enabled", enabled)
        writeRemoteConfig(localConfig)
    }

    fun isHookEnabled(hookName: String): Boolean {
        val packageConfig = getCurrentPackageConfig()
        val hooks = packageConfig.optJSONObject("hooks") ?: return false
        return hooks.optJSONObject(hookName)?.getBoolean("enabled") == true
    }

    fun initHookSettings(name: String, description: String, state: Boolean) {
        val packageConfig = getCurrentPackageConfig()
        val hooks = packageConfig.optJSONObject("hooks")
            ?: JSONObject().also { packageConfig.put("hooks", it) }

        if (hooks.optJSONObject(name) == null) {
            hooks.put(name, JSONObject().apply {
                put("description", description)
                put("enabled", state)
            })

            writeRemoteConfig(localConfig)
        }
    }

    fun getHooksSettings(): Map<String, Pair<String, Boolean>> {
        val packageConfig = getCurrentPackageConfig()
        val hooks = packageConfig.optJSONObject("hooks") ?: return emptyMap()
        val map = mutableMapOf<String, Pair<String, Boolean>>()

        val keys = hooks.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = hooks.getJSONObject(key)
            map[key] = Pair(obj.getString("description"), obj.getBoolean("enabled"))
        }

        return map
    }
}