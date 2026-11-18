package me.weishu.kernelsu.data.repository

import android.content.Context
import androidx.core.content.edit
import me.weishu.kernelsu.ksuApp

/**
 * 模块仓库源。
 *
 * 默认走自建仓库（可以在上面自己上传模块），拉不到时回退官方源 —— "仓库换成自己的"
 * 最怕的就是自建站一挂模块列表整个空白，所以这里永远保留一条兜底路径，并且把
 * **实际生效的源**返还给界面，让用户看得见自己现在看的是谁的仓库。
 */
object ModuleRepoSettings {

    /**
     * 默认主源：我们自己的 GitHub 托管模块仓库（和官方同 schema 的 modules.json）。
     *
     * 走 jsDelivr 而不是 raw.githubusercontent —— 国内手机访问 raw 常被墙，jsDelivr 一般可达；
     * 索引更新后由仓库工作流调 jsDelivr purge 清缓存（否则 CDN 会缓存 12 小时）。
     */
    const val DEFAULT_URL =
        "https://cdn.jsdelivr.net/gh/byBOOK10086/XECKernelPro-Modules@main/modules.json"

    /**
     * 国内镜像（2026-11-18 用户要求：模块仓库源搬进自己的服务器）。
     *
     * 索引与模块 ZIP 都已镜像到 42.193.123.248 的 `/modrepo/`，索引里的 `downloadUrl`
     * 也改写成同一台主机；用默认仓库时它排第一，jsDelivr/raw/官方只做兜底。
     * 用户自己填了仓库地址时不插队——那是他自己的源。
     */
    const val CN_MIRROR_URL = "http://42.193.123.248:5244/d/modrepo/modules.json?v=2"

    /** 我们自己仓库的原始地址：jsDelivr 抽风/没清缓存时的第二顺位。 */
    const val RAW_FALLBACK_URL =
        "https://raw.githubusercontent.com/byBOOK10086/XECKernelPro-Modules/main/modules.json"

    /**
     * 官方仓库，只做最后兜底。
     *
     * 官方索引已经搬到 `api/v1`，并且形状从裸数组变成了 `{schemaVersion, modules:[…]}`：
     * 老的 `modules.json` 现在返回 404 —— 点「官方源」时第三顺位必然落空，用户实际看到的是
     * 第二顺位的自建仓库，界面却什么异常都不报。两个形状 [ModuleRepoRepositoryImpl] 都认。
     */
    const val OFFICIAL_URL = "https://modules.kernelsu.org/api/v1/modules.json"

    /** 历史官方地址：只用于识别用户缓存下来的旧地址，不再作为下载源。 */
    const val OFFICIAL_LEGACY_URL = "https://modules.kernelsu.org/modules.json"

    private const val PREFS = "settings"
    private const val KEY_MODULE_REPO_URL = "module_repo_url"

    private fun prefs() = ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户填的地址；空串 = 用默认自建仓库。 */
    var configuredUrl: String
        get() = prefs().getString(KEY_MODULE_REPO_URL, "").orEmpty().trim()
        set(value) = prefs().edit { putString(KEY_MODULE_REPO_URL, value.trim()) }

    /** 主源（自建仓库）。 */
    val primaryUrl: String
        get() = configuredUrl.ifEmpty { DEFAULT_URL }

    /**
     * 依次尝试的源。
     *
     * 用默认仓库：国内镜像 → jsDelivr → raw → 官方兜底；
     * 用户自填仓库：用户源优先 → 本仓库 raw → 官方兜底（不插国内镜像，
     * 否则"我填了自己的源却看到别人的索引"这种错会很难查）。
     */
    fun sources(): List<String> {
        val custom = configuredUrl
        return if (custom.isEmpty()) {
            listOf(CN_MIRROR_URL, DEFAULT_URL, RAW_FALLBACK_URL, OFFICIAL_URL).distinct()
        } else {
            listOf(custom, RAW_FALLBACK_URL, OFFICIAL_URL).distinct()
        }
    }

    /** 展示用短标签：去掉协议与路径，只留主机（含端口）。 */
    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').ifEmpty { url }

    /** 给人看的源名字：自家仓库/官方仓库说清楚，其它情况退回主机名。 */
    fun labelOf(url: String): String = when {
        url.contains("42.193.123.248:5244/d/modrepo") -> "XECKernel Ultra 模块仓库（国内镜像）"

        url.contains("XECKernelPro-Modules") && url.contains("jsdelivr") ->
            "XECKernel Ultra 模块仓库（jsDelivr）"

        url.contains("XECKernelPro-Modules") -> "XECKernel Ultra 模块仓库（GitHub raw）"
        url == OFFICIAL_URL || url == OFFICIAL_LEGACY_URL -> "官方模块仓库"
        else -> hostOf(url)
    }
}
