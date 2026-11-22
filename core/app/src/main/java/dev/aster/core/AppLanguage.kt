package dev.aster.core

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

private enum class AppLanguage(val tag: String, @StringRes val label: Int) {
    SYSTEM("", R.string.app_language_system),
    CHINESE("zh-Hans", R.string.app_language_chinese),
    ENGLISH("en-US", R.string.app_language_english);

    companion object {
        fun fromTag(tag: String): AppLanguage {
            val language = Locale.forLanguageTag(tag).language
            return entries.firstOrNull { Locale.forLanguageTag(it.tag).language == language } ?: SYSTEM
        }
    }
}

object AppLanguages {
    private const val PREFERENCES = "aster-app-language"
    private const val LANGUAGE_TAG = "language-tag"

    fun legacyTag(context: Context): String =
        if (Build.VERSION.SDK_INT < 33) legacyChoice(context).tag else ""

    fun wrapLegacyContext(base: Context): Context {
        val tag = legacyTag(base)
        if (tag.isEmpty()) return base
        return base.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags(tag))
        })
    }

    fun migrateLegacyChoice(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.contains(LANGUAGE_TAG)) return
        val manager = localeManager(context)
        val choice = legacyChoice(context)
        preferences.edit().remove(LANGUAGE_TAG).apply()
        if (manager.applicationLocales.isEmpty && choice != AppLanguage.SYSTEM) {
            manager.applicationLocales = LocaleList.forLanguageTags(choice.tag)
        }
    }

    @Composable
    fun Picker(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        var choice by remember(context, configuration) { mutableStateOf(choice(context)) }
        var expanded by remember { mutableStateOf(false) }
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_language), Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(stringResource(choice.label)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppLanguage.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(stringResource(item.label)) }, onClick = {
                            expanded = false
                            if (item != choice) {
                                select(context, item)
                                choice = item
                            }
                        })
                    }
                }
            }
        }
    }

    private fun choice(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33) {
        AppLanguage.fromTag(localeManager(context).applicationLocales[0]?.toLanguageTag().orEmpty())
    } else legacyChoice(context)

    private fun legacyChoice(context: Context): AppLanguage = AppLanguage.fromTag(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_TAG, "").orEmpty()
    )

    private fun select(context: Context, choice: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            localeManager(context).applicationLocales = LocaleList.forLanguageTags(choice.tag)
        } else {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(LANGUAGE_TAG, choice.tag).apply()
            activity(context)?.recreate()
        }
    }

    private tailrec fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }

    @RequiresApi(33)
    private fun localeManager(context: Context): LocaleManager =
        checkNotNull(context.getSystemService(LocaleManager::class.java))
}
