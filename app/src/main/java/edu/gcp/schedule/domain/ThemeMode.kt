package edu.gcp.schedule.domain

/**
 * 应用主题模式。跟随系统是默认值；用户显式选浅/深后，系统切换不再影响。
 * 持久化在显示偏好里（DataStore，键 theme_mode），MainActivity 在 setContent 外层解析。
 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}
