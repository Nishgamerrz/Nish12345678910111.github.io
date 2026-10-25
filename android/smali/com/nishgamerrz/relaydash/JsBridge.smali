.class public Lcom/nishgamerrz/relaydash/JsBridge;
.super Ljava/lang/Object;
.source "JsBridge.java"


# instance fields
.field private final activity:Lcom/nishgamerrz/relaydash/MainActivity;


# direct methods
.method public constructor <init>(Lcom/nishgamerrz/relaydash/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    return-void
.end method


# virtual methods
.method public copy(Ljava/lang/String;)V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    new-instance v1, Lcom/nishgamerrz/relaydash/JsBridge$3;

    invoke-direct {v1, v0, p1}, Lcom/nishgamerrz/relaydash/JsBridge$3;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V

    invoke-virtual {v0, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method public load(Ljava/lang/String;)V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    new-instance v1, Lcom/nishgamerrz/relaydash/JsBridge$1;

    invoke-direct {v1, v0, p1}, Lcom/nishgamerrz/relaydash/JsBridge$1;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V

    invoke-virtual {v0, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method public open(Ljava/lang/String;)V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    new-instance v1, Lcom/nishgamerrz/relaydash/JsBridge$4;

    invoke-direct {v1, v0, p1}, Lcom/nishgamerrz/relaydash/JsBridge$4;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V

    invoke-virtual {v0, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method public ping(Ljava/lang/String;)V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    new-instance v1, Lcom/nishgamerrz/relaydash/JsBridge$5;

    invoke-direct {v1, v0, p1}, Lcom/nishgamerrz/relaydash/JsBridge$5;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V

    new-instance v2, Ljava/lang/Thread;

    invoke-direct {v2, v1}, Ljava/lang/Thread;-><init>(Ljava/lang/Runnable;)V

    invoke-virtual {v2}, Ljava/lang/Thread;->start()V

    return-void
.end method

.method public getLanAddress()Ljava/lang/String;
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    :try_start_0
    new-instance v0, Ljava/net/DatagramSocket;

    invoke-direct {v0}, Ljava/net/DatagramSocket;-><init>()V

    const-string v1, "8.8.8.8"

    invoke-static {v1}, Ljava/net/InetAddress;->getByName(Ljava/lang/String;)Ljava/net/InetAddress;
    move-result-object v1

    const/16 v2, 0x35

    invoke-virtual {v0, v1, v2}, Ljava/net/DatagramSocket;->connect(Ljava/net/InetAddress;I)V

    invoke-virtual {v0}, Ljava/net/DatagramSocket;->getLocalAddress()Ljava/net/InetAddress;
    move-result-object v1

    invoke-virtual {v1}, Ljava/net/InetAddress;->getHostAddress()Ljava/lang/String;
    move-result-object v1

    invoke-virtual {v0}, Ljava/net/DatagramSocket;->close()V

    return-object v1
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0

    const-string v0, ""

    return-object v0
.end method

.method public getLaunchUrl()Ljava/lang/String;
    .locals 2

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-virtual {v0}, Lcom/nishgamerrz/relaydash/MainActivity;->getLaunchUrl()Ljava/lang/String;
    move-result-object v0

    if-nez v0, :cond_ret

    const-string v0, ""

    :cond_ret
    return-object v0
.end method

.method public getStartOnBoot()Z
    .locals 2

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-static {v0}, Lcom/nishgamerrz/relaydash/Prefs;->getStartOnBoot(Landroid/content/Context;)Z
    move-result v0

    return v0
.end method

.method public isTv()Z
    .locals 2

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-static {v0}, Lcom/nishgamerrz/relaydash/Prefs;->isTv(Landroid/content/Context;)Z
    move-result v0

    return v0
.end method

.method public openTermux()V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    :try_start_0
    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
    move-result-object v1

    const-string v2, "com.termux"

    invoke-virtual {v1, v2}, Landroid/content/pm/PackageManager;->getLaunchIntentForPackage(Ljava/lang/String;)Landroid/content/Intent;
    move-result-object v1

    if-nez v1, :cond_launch

    const-string v2, "Termux is not installed on this device"

    invoke-virtual {p0, v2}, Lcom/nishgamerrz/relaydash/JsBridge;->toast(Ljava/lang/String;)V

    return-void

    :cond_launch
    const/high16 v2, 0x10000000

    invoke-virtual {v1, v2}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    invoke-virtual {v0, v1}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception v0

    const-string v0, "Could not open Termux"

    invoke-virtual {p0, v0}, Lcom/nishgamerrz/relaydash/JsBridge;->toast(Ljava/lang/String;)V

    return-void
.end method

.method public runConsoleCommand(Ljava/lang/String;)Ljava/lang/String;
    .locals 8

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-direct {p0}, Lcom/nishgamerrz/relaydash/JsBridge;->termuxInstalled()Z
    move-result v1

    if-eqz v1, :cond_has_termux

    const-string v0, "no-termux"

    return-object v0

    :cond_has_termux
    const-string v1, "com.termux.permission.RUN_COMMAND"

    invoke-virtual {v0, v1}, Landroid/app/Activity;->checkSelfPermission(Ljava/lang/String;)I
    move-result v2

    if-nez v2, :cond_has_perm

    new-instance v2, Lcom/nishgamerrz/relaydash/JsBridge$7;

    invoke-direct {v2, v0}, Lcom/nishgamerrz/relaydash/JsBridge$7;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;)V

    invoke-virtual {v0, v2}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V

    const-string v0, "permission-requested"

    return-object v0

    :cond_has_perm
    :try_start_0
    new-instance v2, Landroid/content/Intent;

    invoke-direct {v2}, Landroid/content/Intent;-><init>()V

    const-string v3, "com.termux"

    const-string v4, "com.termux.app.RunCommandService"

    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    const-string v3, "com.termux.RUN_COMMAND"

    invoke-virtual {v2, v3}, Landroid/content/Intent;->setAction(Ljava/lang/String;)Landroid/content/Intent;

    const-string v3, "com.termux.RUN_COMMAND_PATH"

    const-string v4, "/data/data/com.termux/files/usr/bin/bash"

    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    const/4 v3, 0x2

    new-array v4, v3, [Ljava/lang/String;

    const/4 v5, 0x0

    const-string v6, "-c"

    aput-object v6, v4, v5

    const/4 v5, 0x1

    aput-object p1, v4, v5

    const-string v5, "com.termux.RUN_COMMAND_ARGUMENTS"

    invoke-virtual {v2, v5, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;[Ljava/lang/String;)Landroid/content/Intent;

    const-string v3, "com.termux.RUN_COMMAND_WORKDIR"

    const-string v4, "/data/data/com.termux/files/home"

    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    const-string v3, "com.termux.RUN_COMMAND_BACKGROUND"

    const/4 v4, 0x0

    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Z)Landroid/content/Intent;

    invoke-virtual {v0, v2}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;
    move-result-object v2

    if-nez v2, :cond_sent

    const-string v0, "failed"

    return-object v0

    :cond_sent
    const-string v0, "sent"

    return-object v0
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0

    const-string v0, "failed"

    return-object v0
.end method

.method public setStartOnBoot(Z)V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-static {v0, p1}, Lcom/nishgamerrz/relaydash/Prefs;->setStartOnBoot(Landroid/content/Context;Z)V

    if-eqz p1, :cond_off

    const-string v1, "This app will open when the device starts"

    goto :goto_toast

    :cond_off
    const-string v1, "This app will not open on its own"

    :goto_toast
    invoke-virtual {p0, v1}, Lcom/nishgamerrz/relaydash/JsBridge;->toast(Ljava/lang/String;)V

    return-void
.end method

.method private termuxInstalled()Z
    .locals 3

    :try_start_0
    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
    move-result-object v0

    const-string v1, "com.termux"

    const/4 v2, 0x0

    invoke-virtual {v0, v1, v2}, Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;

    const/4 v0, 0x1

    return v0
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0

    const/4 v0, 0x0

    return v0
.end method

.method public toast(Ljava/lang/String;)V
    .locals 4

    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    new-instance v1, Lcom/nishgamerrz/relaydash/JsBridge$2;

    invoke-direct {v1, v0, p1}, Lcom/nishgamerrz/relaydash/JsBridge$2;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V

    invoke-virtual {v0, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method
