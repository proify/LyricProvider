package io.github.proify.lyricon.paprovider.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.highcapable.yukihookapi.hook.factory.prefs
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.paprovider.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    private fun initViews() {
        // 1. 初始化状态
        binding.switchTranslation.isChecked = prefs().get(Config.ENABLE_TRANSLATION)
        binding.switchNetSearch.isChecked = prefs().get(Config.ENABLE_NET_SEARCH)
        binding.switchAutoSave.isChecked = prefs().get(Config.ENABLE_AUTO_SAVE)
        
        updateAutoSaveState(binding.switchNetSearch.isChecked)

        // 2. 设置监听器并记录日志
        binding.switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            YLog.debug("[设置] 翻译显示 -> $isChecked")
            prefs().edit { put(Config.ENABLE_TRANSLATION, isChecked) }
        }

        binding.switchNetSearch.setOnCheckedChangeListener { _, isChecked ->
            YLog.debug("[设置] 云端搜索 -> $isChecked")
            prefs().edit { put(Config.ENABLE_NET_SEARCH, isChecked) }
            updateAutoSaveState(isChecked)
        }

        binding.switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            YLog.debug("[设置] 自动保存 -> $isChecked")
            prefs().edit { put(Config.ENABLE_AUTO_SAVE, isChecked) }
        }
        
        setSupportActionBar(binding.toolbar)
    }

    private fun updateAutoSaveState(isNetSearchEnabled: Boolean) {
        binding.switchAutoSave.isEnabled = isNetSearchEnabled
        // 视觉提示：禁用时降低透明度
        binding.switchAutoSave.alpha = if (isNetSearchEnabled) 1.0f else 0.4f
    }
}