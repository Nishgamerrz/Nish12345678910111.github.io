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
