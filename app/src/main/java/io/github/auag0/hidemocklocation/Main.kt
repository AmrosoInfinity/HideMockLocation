package io.github.auag0.hidemocklocation

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class Main : XposedModule() {
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        hookLocationMethods(param.classLoader)
        hookSettingsMethods(param.classLoader)
        hookAppOpsMethods(param.classLoader)
    }

    @SuppressLint("SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun hookLocationMethods(classLoader: ClassLoader) {
        val locationClass = classLoader.loadClass("android.location.Location")

        hookAllMethods(locationClass, "isFromMockProvider") { _ -> false }
        hookAllMethods(locationClass, "isMock") { _ -> false }

        hookAllMethods(locationClass, "setIsFromMockProvider") { chain ->
            val args = chain.args.toTypedArray()
            args[0] = false
            chain.proceed(args)
        }

        hookAllMethods(locationClass, "setMock") { chain ->
            val args = chain.args.toTypedArray()
            args[0] = false
            chain.proceed(args)
        }

        hookAllMethods(locationClass, "getExtras") { chain ->
            val extras = chain.proceed() as Bundle?
            return@hookAllMethods getPatchedBundle(extras)
        }

        hookAllMethods(locationClass, "setExtras") { chain ->
            val args = chain.args.toTypedArray()
            args[0] = getPatchedBundle(args[0] as Bundle?)
            chain.proceed(args)
        }

        // Hook getProvider() to normalize mock provider names.
        // Known legitimate providers; anything else is treated as a mock name.
        val knownProviders = setOf("gps", "network", "passive", "fused")
        hookAllMethods(locationClass, "getProvider") { chain ->
            val provider = chain.proceed() as? String ?: return@hookAllMethods null
            if (provider !in knownProviders) "gps" else provider
        }

        val hasMockProviderMaskField = runCatching {
            locationClass.getDeclaredField("HAS_MOCK_PROVIDER_MASK").apply { isAccessible = true }
        }.getOrNull()
        val mFieldsMaskField = runCatching {
            locationClass.getDeclaredField("mFieldsMask").apply { isAccessible = true }
        }.getOrNull()
        val mExtrasField = runCatching {
            locationClass.getDeclaredField("mExtras").apply { isAccessible = true }
        }.getOrNull()

        hookAllMethods(locationClass, "set") { chain ->
            chain.proceed()

            if (hasMockProviderMaskField != null && mFieldsMaskField != null) {
                val hasMockProviderMask = hasMockProviderMaskField.getInt(null)
                var mFieldsMask = mFieldsMaskField.getInt(chain.thisObject)
                mFieldsMask = mFieldsMask and hasMockProviderMask.inv()
                mFieldsMaskField.setInt(chain.thisObject, mFieldsMask)
            }

            if (mExtrasField != null) {
                val mExtras = mExtrasField.get(chain.thisObject) as? Bundle?
                mExtrasField.set(chain.thisObject, getPatchedBundle(mExtras))
            }
        }
    }

    private fun hookSettingsMethods(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass($$"android.provider.Settings$Secure")
        hookAllMethods(clazz, "getStringForUser") { chain ->
            val name = chain.args.getOrNull(1) as? String?
            if (name == "mock_location") "0" else chain.proceed()
        }
    }

    // Hook AppOpsManager check methods to hide mock location permission.
    // Only check-type methods are hooked (not noteOp), so the mock location
    // app itself can still record its own operations normally.
    private fun hookAppOpsMethods(classLoader: ClassLoader) {
        val appOpsClass = runCatching {
            classLoader.loadClass("android.app.AppOpsManager")
        }.getOrNull() ?: return

        val checkMethods = listOf(
            "checkOp", "checkOpNoThrow",
            "unsafeCheckOp", "unsafeCheckOpNoThrow"
        )
        for (methodName in checkMethods) {
            hookAllMethods(appOpsClass, methodName) { chain ->
                if (isMockLocationOp(chain.args.firstOrNull())) {
                    return@hookAllMethods AppOpsManager.MODE_ERRORED
                }
                chain.proceed()
            }
        }
    }

    // Returns a copy of the bundle with "mockLocation" forced to false,
    // or the original bundle unchanged if the key is absent.
    private fun getPatchedBundle(origBundle: Bundle?): Bundle? {
        if (origBundle?.containsKey("mockLocation") == true) {
            return Bundle(origBundle).apply { putBoolean("mockLocation", false) }
        }
        return origBundle
    }

    private fun isMockLocationOp(op: Any?): Boolean = when (op) {
        // "android:mock_location"
        // AppOpsManager.OP_MOCK_LOCATION
        is String -> op == AppOpsManager.OPSTR_MOCK_LOCATION
        is Int -> op == 58
        else -> false
    }

    private fun hookAllMethods(clazz: Class<*>, methodName: String, hooker: XposedInterface.Hooker) {
        clazz.declaredMethods
            .filter { it.name == methodName }
            .forEach { method -> hook(method).intercept(hooker) }
    }
}