pluginManagement {
    // allows storing plugin versions in gradle.properties
    fun prop(prop: String): String = providers.gradleProperty(prop).get()
    fun plugin(prop: String): String = prop("plugin.$prop")
    fun mtk(prop: String): String = plugin("mtk.$prop")

    plugins {
        kotlin("jvm") version plugin("kotlin")
        id("dev.isxander.mtk.manifests") version mtk("manifests")
        id("me.modmuss50.mod-publish-plugin") version plugin("mod-publish-plugin")
        id("dev.isxander.modstitch.base") version plugin("modstitch")
        id("dev.kikugie.stonecutter") version plugin("stonecutter")
        id("net.fabricmc.fabric-loom") version prop("fabric.loom") apply false
    }

    repositories {
        // ★ chatstudy 补丁：maven.kikugie.dev 的构件本机可正常下载（已用独立 JVM 进程验证 HTTP 200，
        // 连打 20 次全部成功），但 Gradle 的插件解析稳定报 "plugin not found"。
        // 这里加一个本地镜像仓库兜底（由 _vendor/mirror-kikugie.ps1 生成），放最前面优先命中。
        maven("file:///C:/Users/yushu/Documents/deepseek-harness/default-workspace/_vendor/local-maven")
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        maven("https://maven.parchmentmc.org")
        maven("https://maven.isxander.dev/releases/")
    }
}

dependencyResolutionManagement {
    repositories {
        // ★ chatstudy 补丁：同上，本地镜像优先
        maven("file:///C:/Users/yushu/Documents/deepseek-harness/default-workspace/_vendor/local-maven")
        maven("https://maven.kikugie.dev/snapshots")
    }

    // ★ chatstudy 补丁：摘掉 FT 的版本目录。
    // 理由：build.gradle.kts 里一次都没用 libs.，这个 catalog 提供的访问器无人使用；
    // 而它（fletching-table.catalog）会在 settings 上开依赖锁定，导致
    // "Unable to load unique lockfile / settings-gradle.lockfile" 而整个构建起不来。
    // 原始内容已备份在 _vendor/archive/settings.gradle.kts.chatstudy
    //val ftVersion = providers.gradleProperty("plugin.fletching-table-catalog").get()
    //versionCatalogs {
    //    create("ft") { from("dev.kikugie.fletching-table:fletching-table.catalog:$ftVersion-SNAPSHOT") }
    //}
}

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        //       modstitch compat key:
        //           /-----------|------|.... // neoforge
        // ----|----/                         // forge
        // 1.20   1.20.1      1.20.2  1.20.3

        val loaders = providers.gradleProperty("loaders").get()
        val loadList = loaders.replace("neoforge", "neo").split(",")
        val targets = providers.gradleProperty("targets").get().split(',').map(String::trim)
        targets.forEach {
            v: String ->
            loadList.forEach {
                l: String ->
                version("$v-$l", v)
            }
        }

        // sets vcsVersion to the most recent Fabric version **that's not a full release**
        // starts at the end (most recent versions) to avoid unnecessary file reads
        for(v in targets.reversed()) {
            val vFab = "$v-fabric"
            val props = rootProject.projectDir.resolve("versions/$vFab/gradle.properties") // i know this is ugly but the vcsVersion warnings pmo

            if(props.exists()) {
                val content = props.readText()

                // the \n ensures it's not present but commented out
                if(content.contains("\nmod.nonReleaseComponent=")) {
                    println("Skipped consideration of $vFab as `vcsVersion` because it's not a full release")
                    continue
                } else {
                    vcsVersion = vFab
                    break
                }
            }
        }
    }
}

rootProject.name = "Chat Patches"