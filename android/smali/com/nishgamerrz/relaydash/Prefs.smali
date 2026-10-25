.class public final Lcom/nishgamerrz/relaydash/Prefs;
.super Ljava/lang/Object;
.source "Prefs.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static getStartOnBoot(Landroid/content/Context;)Z
    .locals 3

    :try_start_0
    invoke-static {p0}, Lcom/nishgamerrz/relaydash/Prefs;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0

    const-string v1, "startOnBoot"

    invoke-interface {v0, v1}, Landroid/content/SharedPreferences;->contains(Ljava/lang/String;)Z
    move-result v2

    if-eqz v2, :cond_tv_default

    const/4 v2, 0x0

    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
    move-result v0

    return v0

    :cond_tv_default
    invoke-static {p0}, Lcom/nishgamerrz/relaydash/Prefs;->isTv(Landroid/content/Context;)Z
    move-result v0

    return v0
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0

    const/4 v0, 0x0

    return v0
.end method

.method public static isTv(Landroid/content/Context;)Z
    .locals 2

    :try_start_0
    invoke-virtual {p0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
    move-result-object v0

    const-string v1, "android.software.leanback"

    invoke-virtual {v0, v1}, Landroid/content/pm/PackageManager;->hasSystemFeature(Ljava/lang/String;)Z
    move-result v0

    return v0
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0

    const/4 v0, 0x0

    return v0
.end method

.method private static prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    .locals 2

    const-string v0, "relay"

    const/4 v1, 0x0

    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0

    return-object v0
.end method

.method public static setStartOnBoot(Landroid/content/Context;Z)V
    .locals 2

    invoke-static {p0}, Lcom/nishgamerrz/relaydash/Prefs;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0

    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v0

    const-string v1, "startOnBoot"

    invoke-interface {v0, v1, p1}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;
    move-result-object v0

    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V

    return-void
.end method
