package com.homeos.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import com.homeos.app.databinding.ActivityMainBinding
import java.util.concurrent.Executor

/**
 * 设置面板 UI、语言切换与配置保存逻辑。
 */
class SettingsController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val sharedPreferences: SharedPreferences,
    private val executor: Executor,
    private val isAlive: () -> Boolean,
    private val onSaveConfig: SettingsSavePayload.() -> Unit,
    private val onWipeData: () -> Unit,
    private val onToggleOverlay: (Boolean) -> Unit,
    private val getLocalizedResources: (Context, String) -> android.content.res.Resources
) {
    var tempSelectedLang: String = "zh"
        private set

    fun loadIntoForm(
        lanUrl: String,
        wanUrl: String,
        designWidth: Int,
        designHeight: Int,
        isKioskMode: Boolean,
        lang: String,
        savedUser: String,
        savedPass: String
    ) {
        binding.inputLanUrl.setText(lanUrl)
        binding.inputWanUrl.setText(wanUrl)
        binding.inputDesignWidth.setText(designWidth.toString())
        binding.inputDesignHeight.setText(designHeight.toString())

        binding.inputUsername.setText(savedUser)
        binding.inputPassword.setText(savedPass)

        tempSelectedLang = lang
        updateLanguageToggleUI(lang)

        if (isKioskMode) {
            binding.radioKiosk.isChecked = true
        } else {
            binding.radioCompanion.isChecked = true
        }
    }

    fun setupListeners() {
        binding.layoutAdvancedHeader.setOnClickListener {
            val isExpanded = binding.layoutAdvancedContent.visibility == View.VISIBLE
            binding.layoutAdvancedContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.txtAdvancedArrow.text = if (isExpanded) "▼" else "▲"
        }

        binding.btnLangZhToggle.setOnClickListener {
            tempSelectedLang = "zh"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        binding.btnLangEnToggle.setOnClickListener {
            tempSelectedLang = "en"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        binding.btnCancelSettings.setOnClickListener {
            onToggleOverlay(false)
        }

        binding.btnSaveSettings.setOnClickListener { handleSaveClick() }
        binding.btnWipeData.setOnClickListener { onWipeData() }
    }

    fun applyLanguageToSettingsUI(langCode: String) {
        val res = getLocalizedResources(activity, langCode)

        binding.txtSettingsTitle.text = res.getString(R.string.settings_title)
        binding.txtSettingsDesc.text = res.getString(R.string.settings_desc)
        binding.layoutLanUrl.hint = res.getString(R.string.server_lan_url)
        binding.layoutWanUrl.hint = res.getString(R.string.server_wan_url)
        binding.layoutDesignWidth.hint = res.getString(R.string.design_width)
        binding.layoutDesignHeight.hint = res.getString(R.string.design_height)
        binding.layoutUsername.hint = res.getString(R.string.username)
        binding.layoutPassword.hint = res.getString(R.string.password)
        binding.txtDeviceModeLabel.text = res.getString(R.string.device_mode)
        binding.radioKiosk.text = res.getString(R.string.mode_kiosk)
        binding.radioCompanion.text = res.getString(R.string.mode_companion)
        binding.txtDesignCanvasLabel.text = res.getString(R.string.design_canvas)
        binding.btnCancelSettings.text = res.getString(R.string.cancel)
        binding.btnWipeData.text = res.getString(R.string.btn_wipe_data)

        val currentBtnText = binding.btnSaveSettings.text.toString()
        val saveAnywayStr = res.getString(R.string.save_anyway)
        if (currentBtnText == "强制保存" || currentBtnText == "強制儲存" || currentBtnText == "Save Anyway") {
            binding.btnSaveSettings.text = saveAnywayStr
        } else {
            binding.btnSaveSettings.text = res.getString(R.string.save_settings)
        }

        if (binding.txtVerificationStatus.visibility == View.VISIBLE) {
            val statusText = binding.txtVerificationStatus.text.toString()
            when {
                statusText.contains("Verifying") || statusText.contains("正在验证") || statusText.contains("正在校驗") ->
                    binding.txtVerificationStatus.text = res.getString(R.string.verifying_server)
                statusText.contains("successful") || statusText.contains("成功") ->
                    binding.txtVerificationStatus.text = res.getString(R.string.verification_success)
                statusText.contains("Failed") || statusText.contains("失败") || statusText.contains("失敗") ->
                    binding.txtVerificationStatus.text = res.getString(R.string.verification_failed)
            }
        }
    }

    fun prepareOverlay(show: Boolean) {
        if (show) {
            applyLanguageToSettingsUI(tempSelectedLang)
            binding.txtVerificationStatus.visibility = View.GONE
            binding.btnCancelSettings.visibility =
                if (sharedPreferences.getBoolean("is_configured", false)) View.VISIBLE else View.GONE
            binding.btnWipeData.visibility =
                if (sharedPreferences.getBoolean("is_configured", false)) View.VISIBLE else View.GONE
        }
    }

    private fun handleSaveClick() {
        val lanStr = binding.inputLanUrl.text.toString().trim()
        val wanStr = binding.inputWanUrl.text.toString().trim()
        val userStr = binding.inputUsername.text.toString().trim()
        val passStr = binding.inputPassword.text.toString().trim()
        val isKiosk = binding.radioKiosk.isChecked
        val selectedLang = tempSelectedLang
        val currentRes = getLocalizedResources(activity, selectedLang)
        val parsedDesignWidth = binding.inputDesignWidth.text.toString().trim().toIntOrNull() ?: 3200
        val parsedDesignHeight = binding.inputDesignHeight.text.toString().trim().toIntOrNull() ?: 2136

        if (lanStr.isEmpty()) {
            binding.inputLanUrl.error = currentRes.getString(R.string.lan_url_required)
            return
        }

        val isAnyway = binding.btnSaveSettings.text.toString() == currentRes.getString(R.string.save_anyway)

        if (isAnyway || userStr.isEmpty()) {
            onToggleOverlay(false)
            binding.root.postDelayed({
                if (isAlive()) {
                    onSaveConfig(
                        SettingsSavePayload(
                            lanStr, wanStr, userStr, passStr, "", "",
                            isKiosk, selectedLang, parsedDesignWidth, parsedDesignHeight
                        )
                    )
                }
            }, 150)
            return
        }

        binding.txtVerificationStatus.visibility = View.VISIBLE
        binding.txtVerificationStatus.setTextColor(Color.parseColor("#00E5FF"))
        binding.txtVerificationStatus.text = currentRes.getString(R.string.verifying_server)
        binding.btnSaveSettings.isEnabled = false

        executor.execute {
            var loginResult = HomeOSAuth.performLogin(lanStr, userStr, passStr)
            if (!loginResult.success && wanStr.isNotEmpty() && !wanStr.contains("yourdomain.com")) {
                loginResult = HomeOSAuth.performLogin(wanStr, userStr, passStr)
            }

            val finalResult = loginResult
            activity.runOnUiThread {
                if (!isAlive()) return@runOnUiThread
                binding.btnSaveSettings.isEnabled = true
                val activeRes = getLocalizedResources(activity, tempSelectedLang)
                if (finalResult.success) {
                    binding.txtVerificationStatus.setTextColor(Color.parseColor("#00FF66"))
                    binding.txtVerificationStatus.text = activeRes.getString(R.string.verification_success)
                    binding.root.postDelayed({
                        if (!isAlive()) return@postDelayed
                        onToggleOverlay(false)
                        binding.root.postDelayed({
                            if (isAlive()) {
                                onSaveConfig(
                                    SettingsSavePayload(
                                        lanStr, wanStr, userStr, passStr,
                                        finalResult.authToken, finalResult.csrfToken,
                                        isKiosk, selectedLang, parsedDesignWidth, parsedDesignHeight
                                    )
                                )
                            }
                        }, 150)
                    }, 500)
                } else {
                    binding.txtVerificationStatus.setTextColor(Color.parseColor("#FF3333"))
                    val detail = when {
                        finalResult.requiresMfa -> activity.getString(R.string.mfa_required_suffix)
                        !finalResult.errorMessage.isNullOrEmpty() -> " (${finalResult.errorMessage})"
                        else -> ""
                    }
                    binding.txtVerificationStatus.text =
                        activeRes.getString(R.string.verification_failed) + detail
                    binding.btnSaveSettings.text = activeRes.getString(R.string.save_anyway)
                }
            }
        }
    }

    private fun updateLanguageToggleUI(lang: String) {
        binding.btnLangZhToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangZhToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangZhToggle.setTypeface(null, android.graphics.Typeface.NORMAL)
        binding.btnLangEnToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangEnToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangEnToggle.setTypeface(null, android.graphics.Typeface.NORMAL)

        if (lang == "en") {
            binding.btnLangEnToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            binding.btnLangEnToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
            binding.btnLangEnToggle.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            binding.btnLangZhToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            binding.btnLangZhToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
            binding.btnLangZhToggle.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }
}

data class SettingsSavePayload(
    val lan: String,
    val wan: String,
    val user: String,
    val pass: String,
    val authToken: String,
    val csrfToken: String,
    val isKiosk: Boolean,
    val lang: String,
    val designWidth: Int,
    val designHeight: Int
)
