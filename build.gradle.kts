// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 离线 / 证书受限环境下跳过 distributionUrl 的联网校验（发行版已在本地缓存）。
tasks.wrapper {
    validateDistributionUrl = false
}
