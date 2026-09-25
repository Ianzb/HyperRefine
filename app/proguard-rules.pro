# libxposed 模块入口：由 META-INF/xposed/java_init.list 按类名加载，必须保留
-keep class cn.ianzb.hyperrefine.hook.xposed.XposedEntry { *; }

# 保留全部 Hook 实现（体积很小，避免混淆 / 裁剪破坏按名查找与反射）
-keep class cn.ianzb.hyperrefine.hook.** { *; }

# libxposed API / Service
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# DexKit 大量使用反射，保留类名
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**
