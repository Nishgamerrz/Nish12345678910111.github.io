.class public Lcom/nishgamerrz/relaydash/JsBridge$6;
.super Ljava/lang/Object;
.source "JsBridge.java"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field private final activity:Lcom/nishgamerrz/relaydash/MainActivity;

.field private final js:Ljava/lang/String;


# direct methods
.method public constructor <init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/nishgamerrz/relaydash/JsBridge$6;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    iput-object p2, p0, Lcom/nishgamerrz/relaydash/JsBridge$6;->js:Ljava/lang/String;

    return-void
.end method


# virtual methods
.method public run()V
    .locals 2

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge$6;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    iget-object v1, p0, Lcom/nishgamerrz/relaydash/JsBridge$6;->js:Ljava/lang/String;

    invoke-virtual {v0, v1}, Lcom/nishgamerrz/relaydash/MainActivity;->runJs(Ljava/lang/String;)V

    return-void
.end method
