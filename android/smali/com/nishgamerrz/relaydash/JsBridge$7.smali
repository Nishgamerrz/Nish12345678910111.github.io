.class public Lcom/nishgamerrz/relaydash/JsBridge$7;
.super Ljava/lang/Object;
.source "JsBridge.java"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field private final activity:Lcom/nishgamerrz/relaydash/MainActivity;


# direct methods
.method public constructor <init>(Lcom/nishgamerrz/relaydash/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/nishgamerrz/relaydash/JsBridge$7;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    return-void
.end method


# virtual methods
.method public run()V
    .locals 4

    :try_start_0
    const/4 v0, 0x1

    new-array v1, v0, [Ljava/lang/String;

    const/4 v2, 0x0

    const-string v3, "com.termux.permission.RUN_COMMAND"

    aput-object v3, v1, v2

    const/16 v2, 0x10a2

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge$7;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-virtual {v0, v1, v2}, Landroid/app/Activity;->requestPermissions([Ljava/lang/String;I)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception v0

    return-void
.end method
