package org.openchaos.android.fooping2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import androidx.annotation.Keep
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference


@Keep // PreferenceFragments referenced in layout XML only
@Suppress("unused")
class SettingsFragment : PreferenceFragmentCompat() {
    private val TAG: String = this.javaClass.simpleName


    private fun requirePermission(permission: String): Boolean {
        Log.d(TAG, "requirePermission($permission)")

        if (requireContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "have $permission)")
            return true
        }

        Log.i(TAG, "don't have $permission")

        // TODO: implement rationale dialog?
        if (shouldShowRequestPermissionRationale(permission)) {
            Log.d(TAG, "should show dialog for $permission")
        }

        // TODO: receive permission result and update preference
        requestPermissions(arrayOf(permission), 0)

        return false
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Log.d(TAG, "onCreatePreferences()")

        setPreferencesFromResource(R.xml.preferences, rootKey)

        // request permissions where required
        listOf("ActionLocGPS", "ActionLocNet", "ActionWifi").forEach {
            findPreference<TwoStatePreference>(it)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    requirePermission(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    true
                }
            }
        }

        // set numeric input
        listOf("Port").forEach {
            findPreference<EditTextPreference>(it)?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
        }
    }
}
